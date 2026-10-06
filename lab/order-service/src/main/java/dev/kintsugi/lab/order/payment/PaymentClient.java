package dev.kintsugi.lab.order.payment;

import java.net.http.HttpClient;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Calls payment-service ({@code POST /payments}) over HTTP with explicit timeouts.
 *
 * <p>Every way the call can fail without a usable answer (timeout, connection error, non-2xx response, unknown or
 * missing status) becomes one {@link PaymentUnavailableException}, so the caller handles a single case.
 */
@Component
public class PaymentClient {

	private final RestClient restClient;

	public PaymentClient(PaymentProperties properties) {
		// The JDK client sets the connect timeout; the Spring request factory sets the read timeout per request.
		// HTTP/1.1 avoids the JDK client's h2c upgrade attempt on plain http.
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.build();
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
			throw new PaymentUnavailableException("Payment of order " + orderId + " failed: " + ex.getMessage(), ex);
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
