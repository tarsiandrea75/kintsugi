package dev.kintsugi.lab.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;

/**
 * Full application context against PostgreSQL 18.6, with the real fake gateway: POST, then GET, then check the
 * stored row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void createdPaymentIsStoredAndReadBack() throws Exception {
		UUID orderId = UUID.randomUUID();
		MockHttpServletResponse created = mvc
			.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
					{"orderId": "%s"}
					""".formatted(orderId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andReturn()
			.getResponse();
		String body = created.getContentAsString();
		String id = JsonPath.read(body, "$.id");
		String createdAt = JsonPath.read(body, "$.createdAt");
		String location = created.getHeader("Location");
		assertThat(location).endsWith("/payments/" + id);

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

	private long countPayments() {
		return jdbc.sql("select count(*) from payments").query(Long.class).single();
	}

}
