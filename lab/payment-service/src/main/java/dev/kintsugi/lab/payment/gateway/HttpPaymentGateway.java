package dev.kintsugi.lab.payment.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.kintsugi.lab.payment.domain.PaymentStatus;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;

/**
 * Asks gateway-mock ({@code POST /authorize}) for the outcome of a payment over HTTP with explicit timeouts.
 *
 * <p>A {@code 429 Too Many Requests} is an answer, not a failure: the gateway rejected the payment, so the outcome is
 * {@link PaymentStatus#FAILED} (fault F2). Every way the call can fail without a usable answer (timeout, connection
 * error, any other non-2xx response, unknown or missing status) becomes one {@link GatewayUnavailableException}, so the
 * caller handles a single failure case.
 *
 * <p>Every call is observed as {@code http.client.requests} (Prometheus {@code http_client_requests_seconds_*}), tagged
 * {@code client.name=gateway-mock}, so the payment-service to gateway-mock edge has a caller-side series, and a refused
 * connection and a read timeout show up as different exceptions.
 */
@Component
public class HttpPaymentGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

	/** The {@code client.name} tag; fixed, so a wrong host or IP in the base URL does not split the series. */
	static final String CLIENT_NAME = "gateway-mock";

	private final RestClient restClient;

	/** The full {@code /authorize} URL, carried by every {@link GatewayUnavailableException}. */
	private final URI authorizeUrl;

	public HttpPaymentGateway(GatewayProperties properties, ObservationRegistry observationRegistry) {
		// The JDK client sets the connect timeout; the Spring request factory sets the read timeout per request.
		// HTTP/1.1 avoids the JDK client's h2c upgrade attempt on plain http.
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		this.authorizeUrl = UriComponentsBuilder.fromUri(properties.baseUrl()).path("/authorize").build().toUri();
		this.restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.observationRegistry(observationRegistry)
			.observationConvention(new NamedClientObservationConvention(CLIENT_NAME))
			.build();
	}

	/**
	 * {@inheritDoc}
	 * @throws GatewayUnavailableException if the gateway gives no usable answer
	 */
	@Override
	public PaymentStatus authorize(UUID orderId) {
		AuthorizeReply reply;
		try {
			reply = restClient.post()
				.uri("/authorize")
				.contentType(MediaType.APPLICATION_JSON)
				.body(new AuthorizeRequest(orderId))
				.retrieve()
				.body(AuthorizeReply.class);
		}
		catch (HttpClientErrorException.TooManyRequests ex) {
			// One WARN per failed payment: the line that makes fault F2 visible in the logs.
			log.warn("Gateway rejected the payment of order {} with status {}; recording it as FAILED", orderId,
					HttpStatus.TOO_MANY_REQUESTS.value());
			return PaymentStatus.FAILED;
		}
		catch (RestClientException ex) {
			throw new GatewayUnavailableException("Authorization of order " + orderId + " failed: " + ex.getMessage(),
					authorizeUrl, ex);
		}
		if (reply == null || reply.status() == null) {
			throw new GatewayUnavailableException("Authorization of order " + orderId + " returned no status", authorizeUrl,
					null);
		}
		// FAILED is how this service reads a 429; the gateway itself only answers AUTHORIZED or DECLINED.
		if (reply.status() == PaymentStatus.FAILED) {
			throw new GatewayUnavailableException(
					"Authorization of order " + orderId + " returned an unknown status: " + reply.status(), authorizeUrl,
					null);
		}
		return reply.status();
	}

	/**
	 * The default tags with two changes. {@code client.name} is fixed instead of the request host. {@code exception}
	 * names the I/O exception inside a {@link ResourceAccessException}: by default every I/O failure is tagged
	 * {@code ResourceAccessException}, so a refused connection and a read timeout would look the same. Same convention
	 * as order-service's clients.
	 */
	private static final class NamedClientObservationConvention extends DefaultClientRequestObservationConvention {

		private final String clientName;

		NamedClientObservationConvention(String clientName) {
			this.clientName = clientName;
		}

		@Override
		protected KeyValue clientName(ClientRequestObservationContext context) {
			return KeyValue.of(ClientHttpObservationDocumentation.LowCardinalityKeyNames.CLIENT_NAME, clientName);
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

	private record AuthorizeRequest(UUID orderId) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record AuthorizeReply(PaymentStatus status) {
	}

}
