package dev.kintsugi.lab.payment.api;

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

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.gateway.FakeGatewayServer;

/**
 * Full application context against PostgreSQL 18.6 and a fake gateway-mock over real HTTP: POST, then GET, then
 * check the stored row. The read timeout is shortened so the timeout case stays fast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

	private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

	private static final FakeGatewayServer GATEWAY = FakeGatewayServer.start();

	@DynamicPropertySource
	static void gateway(DynamicPropertyRegistry registry) {
		registry.add("kintsugi.gateway.base-url", GATEWAY::baseUrl);
		registry.add("kintsugi.gateway.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");
	}

	@AfterAll
	static void stopGateway() {
		GATEWAY.close();
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void gatewayAuthorizes() {
		GATEWAY.replyWith(200, "AUTHORIZED");
	}

	@Test
	void createdPaymentIsStoredAndReadBack() throws Exception {
		UUID orderId = UUID.randomUUID();
		MockHttpServletResponse created = mvc
			.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyFor(orderId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andReturn()
			.getResponse();
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.id");
		String createdAt = JsonPath.read(body, "$.createdAt");
		String location = created.getHeader("Location");
		assertThat(location).endsWith("/payments/" + id);
		assertThat(GATEWAY.lastRequest().body()).contains(orderId.toString());

		mvc.perform(get(URI.create(location)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.createdAt").value(createdAt));

		Map<String, Object> row = jdbc.sql("select order_id, status from payments where id = ?")
			.param(UUID.fromString(id))
			.query()
			.singleRow();
		assertThat(row).containsEntry("order_id", orderId).containsEntry("status", "AUTHORIZED");
		assertThat(eventsOf(orderId)).singleElement()
			.satisfies(event -> assertThat(event).containsEntry("type", "PAYMENT_ATTEMPT")
				.containsEntry("outcome", "AUTHORIZED")
				.containsEntry("detail", "{\"paymentId\":\"" + id + "\"}"));
	}

	@Test
	void declinedPaymentIsStoredAsDeclined() throws Exception {
		GATEWAY.replyWith(200, "DECLINED");

		UUID orderId = UUID.randomUUID();

		String body = mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyFor(orderId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DECLINED"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Map<String, Object> row = jdbc.sql("select order_id, status from payments where id = ?")
			.param(UUID.fromString(JsonPath.read(body, "$.id")))
			.query()
			.singleRow();
		assertThat(row).containsEntry("order_id", orderId).containsEntry("status", "DECLINED");
		assertThat(eventsOf(orderId)).singleElement()
			.satisfies(event -> assertThat(event).containsEntry("outcome", "DECLINED"));
	}

	@Test
	void rateLimitedPaymentIsStoredAsFailed() throws Exception {
		GATEWAY.replyWithBody(429, "{\"error\": \"rate limited\"}");

		UUID orderId = UUID.randomUUID();

		String body = mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyFor(orderId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Map<String, Object> row = jdbc.sql("select order_id, status from payments where id = ?")
			.param(UUID.fromString(JsonPath.read(body, "$.id")))
			.query()
			.singleRow();
		assertThat(row).containsEntry("order_id", orderId).containsEntry("status", "FAILED");
		assertThat(eventsOf(orderId)).singleElement()
			.satisfies(event -> assertThat(event).containsEntry("outcome", "FAILED"));
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 503 })
	void failingGatewayIsServiceUnavailableAndStoresOnlyTheAttempt(int code) throws Exception {
		GATEWAY.replyWithBody(code, "{}");
		long before = countPayments();
		UUID orderId = UUID.randomUUID();

		mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyFor(orderId)))
			.andExpect(status().isServiceUnavailable());

		assertThat(countPayments()).isEqualTo(before);
		assertGatewayUnavailableRecorded(orderId);
	}

	@Test
	void slowGatewayIsServiceUnavailableAndStoresOnlyTheAttempt() throws Exception {
		GATEWAY.replyAfter(READ_TIMEOUT.multipliedBy(5));
		long before = countPayments();
		UUID orderId = UUID.randomUUID();

		mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(bodyFor(orderId)))
			.andExpect(status().isServiceUnavailable());

		assertThat(countPayments()).isEqualTo(before);
		Map<String, Object> event = assertGatewayUnavailableRecorded(orderId);
		assertThat((String) event.get("detail")).contains("Timeout");
		assertThat((Long) event.get("duration_ms")).isGreaterThanOrEqualTo(READ_TIMEOUT.toMillis());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"orderId\": \"not-a-uuid\"}", "{\"orderId\": \"" })
	void invalidPaymentIsNotStored(String body) throws Exception {
		long before = countPayments();

		mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());

		assertThat(countPayments()).isEqualTo(before);
	}

	@Test
	void unknownPaymentIsNotFound() throws Exception {
		mvc.perform(get("/payments/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
	}

	/** The attempt is stored with the exception and the gateway URL, and no payment row exists for the order. */
	private Map<String, Object> assertGatewayUnavailableRecorded(UUID orderId) {
		Long payments = jdbc.sql("select count(*) from payments where order_id = ?")
			.param(orderId)
			.query(Long.class)
			.single();
		assertThat(payments).isZero();
		List<Map<String, Object>> events = eventsOf(orderId);
		assertThat(events).singleElement()
			.satisfies(event -> assertThat(event).containsEntry("type", "PAYMENT_ATTEMPT")
				.containsEntry("outcome", "GATEWAY_UNAVAILABLE"));
		Map<String, Object> event = events.getFirst();
		assertThat((String) event.get("detail")).contains("\"exception\":")
			.contains("\"url\":\"" + GATEWAY.baseUrl() + "/authorize\"");
		assertThat(event.get("duration_ms")).isNotNull();
		return event;
	}

	private List<Map<String, Object>> eventsOf(UUID orderId) {
		return jdbc.sql("select type, outcome, detail, duration_ms from payment_events where order_id = ?")
			.param(orderId)
			.query()
			.listOfRows();
	}

	private long countPayments() {
		return jdbc.sql("select count(*) from payments").query(Long.class).single();
	}

	private static String bodyFor(UUID orderId) {
		return """
				{"orderId": "%s"}
				""".formatted(orderId);
	}

}
