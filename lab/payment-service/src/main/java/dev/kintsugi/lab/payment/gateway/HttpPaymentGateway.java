package dev.kintsugi.lab.payment.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/**
 * Asks gateway-mock ({@code POST /authorize}) for the outcome of a payment over HTTP with explicit timeouts.
 *
 * <p>A {@code 429 Too Many Requests} is an answer, not a failure: the gateway rejected the payment, so the outcome is
 * {@link PaymentStatus#FAILED} (fault F2). Every way the call can fail without a usable answer (timeout, connection
 * error, any other non-2xx response, unknown or missing status) becomes one {@link GatewayUnavailableException}, so the
 * caller handles a single failure case.
 */
@Component
public class HttpPaymentGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

	private final RestClient restClient;

	/** The full {@code /authorize} URL, carried by every {@link GatewayUnavailableException}. */
	private final URI authorizeUrl;

	public HttpPaymentGateway(GatewayProperties properties) {
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

	private record AuthorizeRequest(UUID orderId) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record AuthorizeReply(PaymentStatus status) {
	}

}
