package dev.kintsugi.lab.payment.gateway;

import java.net.http.HttpClient;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/**
 * Asks gateway-mock ({@code POST /authorize}) for the outcome of a payment over HTTP with explicit timeouts.
 *
 * <p>Every way the call can fail without a usable answer (timeout, connection error, non-2xx response, unknown or
 * missing status) becomes one {@link GatewayUnavailableException}, so the caller handles a single case.
 */
@Component
public class HttpPaymentGateway implements PaymentGateway {

	private final RestClient restClient;

	public HttpPaymentGateway(GatewayProperties properties) {
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
		catch (RestClientException ex) {
			throw new GatewayUnavailableException("Authorization of order " + orderId + " failed: " + ex.getMessage(),
					ex);
		}
		if (reply == null || reply.status() == null) {
			throw new GatewayUnavailableException("Authorization of order " + orderId + " returned no status", null);
		}
		return reply.status();
	}

	private record AuthorizeRequest(UUID orderId) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record AuthorizeReply(PaymentStatus status) {
	}

}
