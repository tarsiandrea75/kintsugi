package dev.kintsugi.lab.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;

import dev.kintsugi.lab.inventory.TestcontainersConfiguration;

/** Full application context against PostgreSQL 18.6: reserve through the API, then read the stock back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InventoryApiIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void reservationLowersTheStockReadThroughTheApi() throws Exception {
		int sku1 = available("SKU-1");
		int sku2 = available("SKU-2");
		UUID orderId = UUID.randomUUID();

		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "%s", "items": [{"sku": "SKU-1", "quantity": 2}, {"sku": "SKU-2", "quantity": 1}]}
				""".formatted(orderId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").value(orderId.toString()))
			.andExpect(jsonPath("$.status").value("RESERVED"));

		mvc.perform(get("/stock/SKU-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sku").value("SKU-1"))
			.andExpect(jsonPath("$.available").value(sku1 - 2));
		assertThat(available("SKU-2")).isEqualTo(sku2 - 1);
	}

	@Test
	void unknownSkuIsConflictAndChangesNothing() throws Exception {
		int sku1 = available("SKU-1");

		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "%s", "items": [{"sku": "SKU-1", "quantity": 1}, {"sku": "NO-SUCH-SKU", "quantity": 1}]}
				""".formatted(UUID.randomUUID())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("INSUFFICIENT_STOCK"));

		assertThat(available("SKU-1")).isEqualTo(sku1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"orderId\": \"", "{\"orderId\": \"00000000-0000-0000-0000-000000000001\", "
			+ "\"items\": [{\"sku\": \"SKU-1\", \"quantity\": 0}]}" })
	void invalidReservationChangesNothing(String body) throws Exception {
		int sku1 = available("SKU-1");

		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());

		assertThat(available("SKU-1")).isEqualTo(sku1);
	}

	@Test
	void unknownStockIsNotFound() throws Exception {
		mvc.perform(get("/stock/NO-SUCH-SKU")).andExpect(status().isNotFound());
	}

	private int available(String sku) {
		return jdbc.sql("select available from stock where sku = ?").param(sku).query(Integer.class).single();
	}

}
