package dev.kintsugi.lab.order.payment;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.kintsugi.lab.order.client.ServiceRestClients;
import io.micrometer.observation.ObservationRegistry;

/**
 * Calls payment-service ({@code POST /payments}) over HTTP with explicit timeouts.
 *
 * <p>Every way the call can fail without a usable answer (timeout, connection error, non-2xx response, unknown or
 * missing status) becomes one {@link PaymentUnavailableException}, so the caller handles a single case.
 *
 * <p>Every call is observed as {@code http.client.requests} (Prometheus {@code http_client_requests_seconds_*}), tagged
 * {@code client.name=payment-service}, so a refused connection and a read timeout show up as different exceptions
 * (see {@link ServiceRestClients}).
 */
@Component
public class PaymentClient {

	/** The {@code client.name} tag; fixed, so a wrong host or IP in the base URL does not split the series. */
	static final String CLIENT_NAME = "payment-service";

	private final RestClient restClient;

	private final String paymentsUrl;

	public PaymentClient(PaymentProperties properties, ObservationRegistry observationRegistry) {
		this.paymentsUrl = ServiceRestClients.url(properties.baseUrl(), "/payments");
		this.restClient = ServiceRestClients.create(properties.baseUrl(), properties.connectTimeout(),
				properties.readTimeout(), CLIENT_NAME, observationRegistry);
	}

	/**
	 * Asks payment-service to pay {@code orderId}.
	 * @throws PaymentUnavailableException if payment-service gives no usable answer
	 */
	public PaymentOutcome pay(UUID orderId) {
		PaymentReply reply;
		try {
			reply = restClient.post()
				.uri("/payments")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new PaymentRequest(orderId))
				.retrieve()
				.body(PaymentReply.class);
		}
		catch (RestClientException ex) {
			throw new PaymentUnavailableException(
					"Payment of order " + orderId + " failed: " + ServiceRestClients.describe(ex, "POST " + paymentsUrl),
					ex);
		}
		if (reply == null || reply.status() == null) {
			throw new PaymentUnavailableException("Payment of order " + orderId + " returned no status", null);
		}
		return reply.status();
	}

	private record PaymentRequest(UUID orderId) {
	}

	/** Only the status matters here; the payment id and timestamps stay in payment-service. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record PaymentReply(PaymentOutcome status) {
	}

}
