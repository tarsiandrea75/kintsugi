package dev.kintsugi.mcp.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;

/** Loki's query API ({@code /loki/api/v1}). Queries come only from {@link LogQueries}. */
public class LokiClient extends JsonBackend {

	public LokiClient(RestClient rest) {
		super(rest, "Loki");
	}

	/** Log lines of {@code query} between {@code start} and {@code end}, newest first, at most {@code limit}. */
	JsonNode queryRange(String query, Instant start, Instant end, int limit) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("query", query);
		params.put("start", nanos(start));
		params.put("end", nanos(end));
		params.put("limit", limit);
		params.put("direction", "backward");
		return get("/loki/api/v1/query_range", params);
	}

	/** A metric query evaluated at {@code time}. */
	JsonNode query(String query, Instant time) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("query", query);
		params.put("time", nanos(time));
		return get("/loki/api/v1/query", params);
	}

	private static String nanos(Instant at) {
		return String.valueOf(at.getEpochSecond() * 1_000_000_000L + at.getNano());
	}

}
