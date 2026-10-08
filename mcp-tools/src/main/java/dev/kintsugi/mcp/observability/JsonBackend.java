package dev.kintsugi.mcp.observability;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.stream.Collectors;

import dev.kintsugi.mcp.tool.UpstreamException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * A GET against a Loki or Prometheus HTTP API that answers {@code {"status":"success","data":...}}. Every failure
 * becomes an {@link UpstreamException} with a short reason the model can read.
 */
abstract class JsonBackend {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RestClient rest;

	private final String name;

	JsonBackend(RestClient rest, String name) {
		this.rest = rest;
		this.name = name;
	}

	/**
	 * Returns the {@code data} node. Parameters are passed as URI variables, so they are encoded strictly: braces,
	 * quotes and {@code +} in a query reach the backend as written.
	 */
	JsonNode get(String path, Map<String, ?> params) {
		String template = path + "?" + params.keySet()
			.stream()
			.map(param -> param + "={" + param + "}")
			.collect(Collectors.joining("&"));
		String body;
		try {
			body = this.rest.get().uri(template, params).retrieve().body(String.class);
		}
		catch (RestClientResponseException ex) {
			throw new UpstreamException(this.name + " answered HTTP " + ex.getStatusCode().value(), ex);
		}
		catch (ResourceAccessException ex) {
			throw new UpstreamException(this.name + (isTimeout(ex) ? " did not answer in time" : " is unreachable"),
					ex);
		}
		catch (RestClientException ex) {
			throw new UpstreamException(this.name + " request failed", ex);
		}
		JsonNode root;
		try {
			root = JSON.readTree(body == null ? "" : body);
		}
		catch (JacksonException ex) {
			throw new UpstreamException(this.name + " returned an unreadable answer", ex);
		}
		String status = root.path("status").asString("");
		if (!"success".equals(status)) {
			throw new UpstreamException(this.name + " answered status " + (status.isEmpty() ? "unknown" : status));
		}
		return root.path("data");
	}

	private static boolean isTimeout(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return true;
			}
		}
		return false;
	}

}
