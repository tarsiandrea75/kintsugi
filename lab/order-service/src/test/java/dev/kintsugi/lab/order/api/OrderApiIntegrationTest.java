package dev.kintsugi.lab.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import dev.kintsugi.lab.order.TestcontainersConfiguration;
import dev.kintsugi.lab.order.payment.FakePaymentServer;

/**
 * Full application context against PostgreSQL 18.6 and a fake payment-service over real HTTP: POST, then GET, then
 * check the stored rows. The read timeout is shortened so the timeout case stays fast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderApiIntegrationTest {

	private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

	private static final FakePaymentServer PAYMENT = FakePaymentServer.start();

	@DynamicPropertySource
	static void paymentService(DynamicPropertyRegistry registry) {
		registry.add("kintsugi.payment.base-url", PAYMENT::baseUrl);
		registry.add("kintsugi.payment.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");
	}

	@AfterAll
	static void stopPaymentService() {
		PAYMENT.close();
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void paymentAuthorizes() {
		PAYMENT.replyWith(201, "AUTHORIZED");
	}

	@Test
	void authorizedOrderIsPaidStoredAndReadBack() throws Exception {
		MockHttpServletResponse created = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "customer-e2e", "items": [{"sku": "SKU-1", "quantity": 2}, {"sku": "SKU-2", "quantity": 1}]}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PAID"))
			.andReturn()
			.getResponse();
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.id");
		String createdAt = JsonPath.read(body, "$.createdAt");
		String updatedAt = JsonPath.read(body, "$.updatedAt");
		String location = created.getHeader("Location");
		assertThat(location).endsWith("/orders/" + id);

		mvc.perform(get(URI.create(location)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.customerId").value("customer-e2e"))
			.andExpect(jsonPath("$.items[0].sku").value("SKU-1"))
			.andExpect(jsonPath("$.items[0].quantity").value(2))
			.andExpect(jsonPath("$.items[1].sku").value("SKU-2"))
			.andExpect(jsonPath("$.items[1].quantity").value(1))
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.createdAt").value(createdAt))
			.andExpect(jsonPath("$.updatedAt").value(updatedAt));

		Map<String, Object> row = jdbc.sql("select customer_id, status from orders where id = ?")
			.param(UUID.fromString(id))
			.query()
			.singleRow();
		List<String> skus = jdbc.sql("select sku from order_items where order_id = ? order by item_index")
			.param(UUID.fromString(id))
			.query(String.class)
			.list();
		assertThat(row).containsEntry("customer_id", "customer-e2e").containsEntry("status", "PAID");
		assertThat(skus).containsExactly("SKU-1", "SKU-2");
		assertThat(PAYMENT.lastRequest().body()).contains(id);
	}

	@Test
	void declinedOrderIsStoredAsPaymentFailed() throws Exception {
		PAYMENT.replyWith(201, "DECLINED");

		String id = postValidOrderExpecting("PAYMENT_FAILED");

		assertThat(storedStatus(id)).isEqualTo("PAYMENT_FAILED");
	}

	@Test
	void failedPaymentIsStoredAsPaymentFailed() throws Exception {
		PAYMENT.replyWith(201, "FAILED");

		String id = postValidOrderExpecting("PAYMENT_FAILED");

		assertThat(storedStatus(id)).isEqualTo("PAYMENT_FAILED");
	}

	@Test
	void paymentTimeoutLeavesOrderPendingAndStillCreatesIt() throws Exception {
		PAYMENT.replyAfter(READ_TIMEOUT.multipliedBy(5));

		String id = postValidOrderExpecting("PAYMENT_PENDING");

		assertThat(storedStatus(id)).isEqualTo("PAYMENT_PENDING");
		mvc.perform(get("/orders/{id}", id)).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
	}

	@Test
	void paymentServerErrorLeavesOrderPending() throws Exception {
		PAYMENT.replyWithBody(500, "{}");

		String id = postValidOrderExpecting("PAYMENT_PENDING");

		assertThat(storedStatus(id)).isEqualTo("PAYMENT_PENDING");
	}

	@Test
	void invalidOrderIsNotStored() throws Exception {
		long before = countOrders();

		mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "customer-e2e", "items": [{"sku": "SKU-1", "quantity": 0}]}
				"""))
			.andExpect(status().isBadRequest());

		assertThat(countOrders()).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"""
			{"customerId": "\u2003", "items": [{"sku": "SKU-1", "quantity": 1}]}""",
			"""
			{"customerId": "\u3000", "items": [{"sku": "SKU-1", "quantity": 1}]}""",
			"""
			{"customerId": "customer-e2e", "items": [{"sku": "\u2003", "quantity": 1}]}""",
			"""
			{"customerId": "customer-e2e", "items": [{"sku": "\u3000", "quantity": 1}]}""" })
	void unicodeWhitespaceIdIsRejectedAndNotStored(String body) throws Exception {
		long before = countOrders();

		mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());

		assertThat(countOrders()).isEqualTo(before);
	}

	@Test
	void idsAtMaxLengthFitTheColumns() throws Exception {
		String customerId = "c".repeat(64);
		String sku = "s".repeat(64);

		String body = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "%s", "items": [{"sku": "%s", "quantity": 1}]}
				""".formatted(customerId, sku)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		assertThat(jdbc.sql("select customer_id from orders where id = ?").param(id).query(String.class).single())
			.isEqualTo(customerId);
	}

	@Test
	void unknownOrderIsNotFound() throws Exception {
		mvc.perform(get("/orders/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
	}

	/** Posts a valid order, expects 201 with {@code status}, and returns the order id. */
	private String postValidOrderExpecting(String status) throws Exception {
		String body = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "customer-e2e", "items": [{"sku": "SKU-1", "quantity": 1}]}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value(status))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String storedStatus(String id) {
		return jdbc.sql("select status from orders where id = ?").param(UUID.fromString(id)).query(String.class).single();
	}

	private long countOrders() {
		return jdbc.sql("select count(*) from orders").query(Long.class).single();
	}

}
