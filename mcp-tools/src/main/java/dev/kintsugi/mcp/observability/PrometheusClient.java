package dev.kintsugi.mcp.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;

/** Prometheus' query API ({@code /api/v1}). Queries come only from {@link MetricCatalog} and the restart template. */
public class PrometheusClient extends JsonBackend {

	public PrometheusClient(RestClient rest) {
		super(rest, "Prometheus");
	}

	/** A range query from {@code start} to {@code end}, one point every {@code stepSeconds}. */
	JsonNode queryRange(String query, Instant start, Instant end, long stepSeconds) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("query", query);
		params.put("start", start.getEpochSecond());
		params.put("end", end.getEpochSecond());
		params.put("step", stepSeconds);
		return get("/api/v1/query_range", params);
	}

}
