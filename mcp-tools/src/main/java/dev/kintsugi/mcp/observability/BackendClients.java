package dev.kintsugi.mcp.observability;

import java.net.http.HttpClient;

import dev.kintsugi.mcp.config.BackendProperties;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the {@link RestClient} for one backend, with its base URL and both timeouts. */
public final class BackendClients {

	private BackendClients() {
	}

	public static RestClient restClient(BackendProperties.Backend backend) {
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(backend.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(backend.readTimeout());
		return RestClient.builder().baseUrl(backend.baseUrl().toString()).requestFactory(requestFactory).build();
	}

}
