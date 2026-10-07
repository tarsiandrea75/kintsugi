package dev.kintsugi.lab.order.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;

/**
 * What every client of another lab service shares: a {@link RestClient} with explicit connect and read timeouts whose
 * calls are observed as {@code http.client.requests} under a fixed {@code client.name}, and a readable description
 * of an I/O failure for the WARN line.
 */
public final class ServiceRestClients {

	private ServiceRestClients() {
	}

	/**
	 * A client for {@code baseUrl}. The JDK client sets the connect timeout; the Spring request factory sets the read
	 * timeout per request. HTTP/1.1 avoids the JDK client's h2c upgrade attempt on plain http.
	 * @param clientName the {@code client.name} tag; fixed, so a wrong host or IP in the base URL does not split the
	 * series
	 */
	public static RestClient create(URI baseUrl, Duration connectTimeout, Duration readTimeout, String clientName,
			ObservationRegistry observationRegistry) {
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(connectTimeout)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		return RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.observationRegistry(observationRegistry)
			.observationConvention(new NamedClientObservationConvention(clientName))
			.build();
	}

	/** {@code baseUrl} joined with {@code path}, without a doubled slash; used to name the URL in a WARN line. */
	public static String url(URI baseUrl, String path) {
		return baseUrl.toString().replaceAll("/+$", "") + path;
	}

	/**
	 * Names the I/O exception and the request for an I/O failure, for example
	 * {@code ConnectException calling POST http://payment-service:9999/payments}. Spring's own text for it ends with the
	 * I/O exception's message, which is {@code null} for the JDK client's {@code ConnectException}, so a refused
	 * connection would read as "...: null". Any other failure keeps Spring's message.
	 */
	public static String describe(RestClientException ex, String request) {
		if (ex instanceof ResourceAccessException && ex.getCause() != null) {
			Throwable io = ex.getCause();
			String detail = io.getMessage() == null ? "" : ": " + io.getMessage();
			return io.getClass().getSimpleName() + " calling " + request + detail;
		}
		return ex.getMessage();
	}

	/**
	 * The default tags with two changes. {@code client.name} is fixed instead of the request host. {@code exception}
	 * names the I/O exception inside a {@link ResourceAccessException}: by default every I/O failure is tagged
	 * {@code ResourceAccessException}, so a refused connection (F3) and a read timeout (F1) would look the same.
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

}
