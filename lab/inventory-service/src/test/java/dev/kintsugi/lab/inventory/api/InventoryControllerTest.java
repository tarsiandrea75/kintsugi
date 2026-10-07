package dev.kintsugi.lab.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import dev.kintsugi.lab.inventory.application.InventoryService;
import dev.kintsugi.lab.inventory.domain.Reservation;
import dev.kintsugi.lab.inventory.domain.ReservationLine;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;
import dev.kintsugi.lab.inventory.domain.Stock;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

	private static final UUID ORDER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private InventoryService service;

	@Test
	void reservedStockIsCreated() throws Exception {
		Reservation expected = new Reservation(ORDER_ID,
				List.of(new ReservationLine("SKU-1", 2), new ReservationLine("SKU-2", 1)));
		given(service.reserve(expected)).willReturn(ReservationStatus.RESERVED);

		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "%s", "items": [{"sku": "SKU-1", "quantity": 2}, {"sku": "SKU-2", "quantity": 1}]}
				""".formatted(ORDER_ID)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
			.andExpect(jsonPath("$.status").value("RESERVED"));
	}

	@Test
	void insufficientStockIsConflict() throws Exception {
		given(service.reserve(any())).willReturn(ReservationStatus.INSUFFICIENT_STOCK);

		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
			.andExpect(jsonPath("$.status").value("INSUFFICIENT_STOCK"));
	}

	static List<String> invalidBodies() {
		String item = "{\"sku\": \"SKU-1\", \"quantity\": 1}";
		String hundredAndOne = IntStream.range(0, 101).mapToObj(i -> item).collect(Collectors.joining(","));
		String order = "\"orderId\": \"" + ORDER_ID + "\"";
		return List.of("{}", "{\"items\": [" + item + "]}", "{\"orderId\": \"not-a-uuid\", \"items\": [" + item + "]}",
				"{" + order + "}", "{" + order + ", \"items\": []}", "{" + order + ", \"items\": [null]}",
				"{" + order + ", \"items\": [" + hundredAndOne + "]}",
				"{" + order + ", \"items\": [{\"quantity\": 1}]}",
				"{" + order + ", \"items\": [{\"sku\": \" \", \"quantity\": 1}]}",
				"{" + order + ", \"items\": [{\"sku\": \"" + "S".repeat(65) + "\", \"quantity\": 1}]}",
				"{" + order + ", \"items\": [{\"sku\": \"SKU-1\"}]}",
				"{" + order + ", \"items\": [{\"sku\": \"SKU-1\", \"quantity\": 0}]}",
				"{" + order + ", \"items\": [{\"sku\": \"SKU-1\", \"quantity\": -2}]}", "{\"orderId\": \"");
	}

	@ParameterizedTest
	@MethodSource("invalidBodies")
	void invalidBodyIsRejected(String body) throws Exception {
		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		verify(service, never()).reserve(any());
	}

	@Test
	void exhaustedPoolIsInternalServerError() throws Exception {
		given(service.reserve(any())).willThrow(new CannotCreateTransactionException("Could not open JDBC Connection",
				new java.sql.SQLTransientConnectionException(
						"HikariPool-1 - Connection is not available, request timed out after 250ms")));

		mvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isInternalServerError());
	}

	@Test
	void noConnectionOnReadIsInternalServerError() throws Exception {
		given(service.stock("SKU-1")).willThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));

		mvc.perform(get("/stock/SKU-1")).andExpect(status().isInternalServerError());
	}

	@Test
	void existingStockIsReturned() throws Exception {
		given(service.stock("SKU-3")).willReturn(Optional.of(new Stock("SKU-3", 42)));

		mvc.perform(get("/stock/SKU-3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sku").value("SKU-3"))
			.andExpect(jsonPath("$.available").value(42));
	}

	@Test
	void unknownStockIsNotFound() throws Exception {
		given(service.stock("NO-SUCH-SKU")).willReturn(Optional.empty());

		mvc.perform(get("/stock/NO-SUCH-SKU")).andExpect(status().isNotFound());
	}

	private static String validBody() {
		return """
				{"orderId": "%s", "items": [{"sku": "SKU-1", "quantity": 1}]}
				""".formatted(ORDER_ID);
	}

}
