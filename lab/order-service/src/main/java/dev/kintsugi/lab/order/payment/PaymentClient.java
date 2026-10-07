package dev.kintsugi.lab.order.payment;

import java.net.http.HttpClient;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;

/**
 * Calls payment-service ({@code POST /payments}) over HTTP with explicit timeouts.
 *
 * <p>Every way the call can fail without a usable answer (timeout, connection error, non-2xx response, unknown or
 * missing status) becomes one {@link PaymentUnavailableException}, so the caller handles a single case.
 *
 * <p>Every call is observed as {@code http.client.requests} (Prometheus {@code http_client_requests_seconds_*}), tagged
 * {@code client.name=payment-service}, so a refused connection and a read timeout show up as different exceptions.
 */
@Component
public class PaymentClient {

	/** The {@code client.name} tag; fixed, so a wrong host or IP in the base URL does not split the series. */
	static final String CLIENT_NAME = "payment-service";

	private final RestClient restClient;

	private final String paymentsUrl;

	public PaymentClient(PaymentProperties properties, ObservationRegistry observationRegistry) {
		// The JDK client sets the connect timeout; the Spring request factory sets the read timeout per request.
		// HTTP/1.1 avoids the JDK client's h2c upgrade attempt on plain http.
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		this.paymentsUrl = properties.baseUrl().toString().replaceAll("/+$", "") + "/payments";
		this.restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.observationRegistry(observationRegistry)
			.observationConvention(new PaymentClientObservationConvention())
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
			throw new PaymentUnavailableException("Payment of order " + orderId + " failed: " + describe(ex), ex);
		}
		if (reply == null || reply.status() == null) {
			throw new PaymentUnavailableException("Payment of order " + orderId + " returned no status", null);
		}
		return reply.status();
	}

	/**
	 * The default tags with two changes. {@code client.name} is fixed instead of the request host. {@code exception}
	 * names the I/O exception inside a {@link ResourceAccessException}: by default every I/O failure is tagged
	 * {@code ResourceAccessException}, so a refused connection (F3) and a read timeout (F1) would look the same.
	 */
	private static final class PaymentClientObservationConvention extends DefaultClientRequestObservationConvention {

		@Override
		protected KeyValue clientName(ClientRequestObservationContext context) {
			return KeyValue.of(ClientHttpObservationDocumentation.LowCardinalityKeyNames.CLIENT_NAME, CLIENT_NAME);
		}

		@Override
		protected KeyValue exception(ClientRequestObservationContext context) {
			if (context.getError() instanceof ResourceAccessException ex && ex.getCause() != null) {
				return KeyValue.of(ClientHttpObservationDocumentation.LowCardinalityKeyNames.EXCEPTION,
						ex.getCause().getClass().getSimpleName());
			}
			return super.exception(context);
		}

	}

	/**
	 * Names the I/O exception and the URL for an I/O failure. Spring's own text for it ends with the I/O exception's
	 * message, which is {@code null} for the JDK client's {@code ConnectException}, so a refused connection would read
	 * as "...: null".
	 */
	private String describe(RestClientException ex) {
		if (ex instanceof ResourceAccessException && ex.getCause() != null) {
			Throwable io = ex.getCause();
			String detail = io.getMessage() == null ? "" : ": " + io.getMessage();
			return io.getClass().getSimpleName() + " calling POST " + paymentsUrl + detail;
		}
		return ex.getMessage();
	}

	private record PaymentRequest(UUID orderId) {
	}

	/** Only the status matters here; the payment id and timestamps stay in payment-service. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record PaymentReply(PaymentOutcome status) {
	}

}
