package dev.kintsugi.lab.fault.control;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.catalog.LabMechanism;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies F1 and F2 through gateway-mock's admin API: {@code PUT /admin/behavior} with the fault's catalog parameters
 * as the body, and {@code DELETE /admin/behavior} to restore. Connect and read timeouts bound every call; any error
 * answer, timeout or connection failure is a failed step.
 */
public class HttpGatewayControl implements LabControl {

	private static final String BEHAVIOR = "/admin/behavior";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient http;

	public HttpGatewayControl(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
		HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(connectTimeout)
			.build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
		requests.setReadTimeout(readTimeout);
		this.http = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(requests).build();
	}

	@Override
	public void activate(Fault fault) {
		requireGatewayFault(fault);
		String behavior = JSON.writeValueAsString(fault.parameters());
		call(fault, "PUT", () -> http.put()
			.uri(BEHAVIOR)
			.contentType(MediaType.APPLICATION_JSON)
			.body(behavior)
			.retrieve()
			.toBodilessEntity());
	}

	@Override
	public void restore(Fault fault) {
		requireGatewayFault(fault);
		call(fault, "DELETE", () -> http.delete().uri(BEHAVIOR).retrieve().toBodilessEntity());
	}

	private static void call(Fault fault, String method, Runnable request) {
		try {
			request.run();
		}
		catch (RestClientException failure) {
			throw new LabControlException("gateway-mock " + method + " " + BEHAVIOR + " for " + fault.id() + " failed: "
					+ failure.getMessage(), failure);
		}
	}

	private static void requireGatewayFault(Fault fault) {
		if (fault.mechanism() != LabMechanism.GATEWAY_BEHAVIOR) {
			throw new LabControlException(fault.id() + " is not applied through the gateway-mock admin API");
		}
	}

}
