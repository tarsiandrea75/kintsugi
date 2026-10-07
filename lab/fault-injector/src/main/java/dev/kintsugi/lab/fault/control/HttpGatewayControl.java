package dev.kintsugi.lab.fault.control;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.catalog.LabMechanism;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies F1 and F2 through gateway-mock's admin API: {@code PUT /admin/behavior} with the fault's catalog parameters
 * as the body, and {@code DELETE /admin/behavior} to restore. Connect and read timeouts bound every call; any error
 * answer, timeout, connection failure or serialization error is a failed step ({@link LabControlException}).
 */
public class HttpGatewayControl implements LabControl {

	private static final String BEHAVIOR = "/admin/behavior";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient http;

	private final JsonMapper json;

	public HttpGatewayControl(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
		this(baseUrl, connectTimeout, readTimeout, JSON);
	}

	/** @param json serializes the behavior body; injectable so a test can make it fail */
	HttpGatewayControl(URI baseUrl, Duration connectTimeout, Duration readTimeout, JsonMapper json) {
		this.json = json;
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
		call(fault, "PUT", () -> http.put()
			.uri(BEHAVIOR)
			.contentType(MediaType.APPLICATION_JSON)
			.body(json.writeValueAsString(fault.parameters()))
			.retrieve()
			.toBodilessEntity());
	}

	@Override
	public void restore(Fault fault) {
		requireGatewayFault(fault);
		call(fault, "DELETE", () -> http.delete().uri(BEHAVIOR).retrieve().toBodilessEntity());
	}

	/**
	 * Runs one step, body serialization included, and turns any failure (an error answer, a timeout, a refused
	 * connection, a body that cannot be serialized) into {@link LabControlException}, as the {@link LabControl} contract
	 * requires.
	 */
	private static void call(Fault fault, String method, Runnable request) {
		try {
			request.run();
		}
		catch (RuntimeException failure) {
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
