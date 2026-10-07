package dev.kintsugi.lab.order.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.kintsugi.lab.order.client.ServiceRestClients;
import dev.kintsugi.lab.order.domain.OrderItem;
import io.micrometer.observation.ObservationRegistry;

/**
 * Calls inventory-service ({@code POST /reservations}) over HTTP with explicit timeouts.
 *
 * <p>Two answers are usable: 201 with {@code RESERVED} and 409 with {@code INSUFFICIENT_STOCK}. Every other way the
 * call can end (timeout, connection error, any other status, an unknown or missing status, or a status that
 * contradicts the HTTP code) becomes one {@link InventoryUnavailableException}, so the caller handles a single case.
 *
 * <p>Every call is observed as {@code http.client.requests} (Prometheus {@code http_client_requests_seconds_*}), tagged
 * {@code client.name=inventory-service}, with the I/O exception as the {@code exception} tag (see
 * {@link ServiceRestClients}). Under fault F4 these calls end in {@code status="500"}.
 */
@Component
public class InventoryClient {

	/** The {@code client.name} tag; fixed, so a wrong host or IP in the base URL does not split the series. */
	static final String CLIENT_NAME = "inventory-service";

	private final RestClient restClient;

	private final String reservationsUrl;

	public InventoryClient(InventoryProperties properties, ObservationRegistry observationRegistry) {
		this.reservationsUrl = ServiceRestClients.url(properties.baseUrl(), "/reservations");
		this.restClient = ServiceRestClients.create(properties.baseUrl(), properties.connectTimeout(),
				properties.readTimeout(), CLIENT_NAME, observationRegistry);
	}

	/**
	 * Asks inventory-service to reserve {@code items} for {@code orderId}, all of them or none.
	 * @throws InventoryUnavailableException if inventory-service gives no usable answer
	 */
	public ReservationOutcome reserve(UUID orderId, List<OrderItem> items) {
		ResponseEntity<ReservationReply> response;
		try {
			response = restClient.post()
				.uri("/reservations")
				.contentType(MediaType.APPLICATION_JSON)
				.body(ReservationRequest.of(orderId, items))
				.retrieve()
				// 409 is an answer, not an error: read its body like a 201's.
				.onStatus(status -> status.isSameCodeAs(HttpStatus.CONFLICT), (request, reply) -> {
				})
				.toEntity(ReservationReply.class);
		}
		catch (RestClientException ex) {
			throw new InventoryUnavailableException(orderId, reservationsUrl,
					"Reservation for order " + orderId + " failed: "
							+ ServiceRestClients.describe(ex, "POST " + reservationsUrl),
					ex);
		}
		HttpStatusCode code = response.getStatusCode();
		ReservationOutcome outcome = response.getBody() == null ? null : response.getBody().status();
		if (code.is2xxSuccessful() && outcome == ReservationOutcome.RESERVED) {
			return ReservationOutcome.RESERVED;
		}
		if (code.isSameCodeAs(HttpStatus.CONFLICT) && outcome == ReservationOutcome.INSUFFICIENT_STOCK) {
			return ReservationOutcome.INSUFFICIENT_STOCK;
		}
		throw new InventoryUnavailableException(orderId, reservationsUrl, "Reservation for order " + orderId + " answered "
				+ code.value() + " with status " + outcome + " from POST " + reservationsUrl, null);
	}

	private record ReservationRequest(UUID orderId, List<Item> items) {

		static ReservationRequest of(UUID orderId, List<OrderItem> items) {
			return new ReservationRequest(orderId, items.stream().map(i -> new Item(i.sku(), i.quantity())).toList());
		}

	}

	private record Item(String sku, int quantity) {
	}

	/** Only the status matters here; the order id is the one that was sent. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ReservationReply(ReservationOutcome status) {
	}

}
