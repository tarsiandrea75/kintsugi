package dev.kintsugi.mcp.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * Turns one raw range query over the HTTP server and client counters, and the process start times, into exact counts
 * per edge (mcp-tools spec, "Exact traffic counts over a bounded window").
 */
final class TrafficCounts {

	static final String SERVER_METRIC = "http_server_requests_seconds_count";

	static final String CLIENT_METRIC = "http_client_requests_seconds_count";

	static final String START_TIME_METRIC = "process_start_time_seconds";

	/** Prometheus scrapes every 5 s (infra/prometheus/prometheus.yml), so this step sees every raw sample. */
	static final long STEP_SECONDS = MetricCatalog.MIN_STEP_SECONDS;

	private static final Comparator<String> LABEL_ORDER = Comparator.nullsFirst(Comparator.naturalOrder());

	private static final Comparator<Row> ROW_ORDER = Comparator.comparing(Row::side)
		.thenComparing(Row::service, LABEL_ORDER)
		.thenComparing(Row::clientName, LABEL_ORDER)
		.thenComparing(Row::method, LABEL_ORDER)
		.thenComparing(Row::uri, LABEL_ORDER)
		.thenComparing(Row::status, LABEL_ORDER)
		.thenComparing(Row::outcome, LABEL_ORDER)
		.thenComparing(Row::exception, LABEL_ORDER);

	private TrafficCounts() {
	}

	/** The raw counters and start times of the allowed services, without the {@code /actuator} endpoints. */
	static String query() {
		return "{__name__=~\"" + SERVER_METRIC + "|" + CLIENT_METRIC + "|" + START_TIME_METRIC + "\","
				+ MetricCatalog.serviceMatcher(null) + ",uri!~\"/actuator(/.*)?\"}";
	}

	/** The rows with a count above zero, sorted by side, service, then labels. */
	static List<Row> rows(JsonNode data, long windowStart) {
		Map<String, NavigableMap<Long, Double>> startTimes = new HashMap<>();
		for (JsonNode result : data.path("result")) {
			JsonNode metric = result.path("metric");
			if (START_TIME_METRIC.equals(metric.path("__name__").asString(""))) {
				NavigableMap<Long, Double> times = startTimes.computeIfAbsent(metric.path("service").asString(""),
						service -> new TreeMap<>());
				samples(result).forEach(sample -> times.put(sample.time(), sample.value()));
			}
		}
		Map<Row, Long> counts = new TreeMap<>(ROW_ORDER);
		for (JsonNode result : data.path("result")) {
			Row key = key(result.path("metric"));
			if (key == null) {
				continue;
			}
			NavigableMap<Long, Double> times = startTimes.getOrDefault(key.service(), new TreeMap<>());
			long count = CounterDelta.increments(samples(result), windowStart,
					(before, after) -> restarted(times, before, after));
			counts.merge(key, count, Long::sum);
		}
		List<Row> rows = new ArrayList<>();
		counts.forEach((key, count) -> {
			if (count > 0) {
				rows.add(key.withCount(count));
			}
		});
		return rows;
	}

	/**
	 * The row a series adds to, without its count: server series by service, method, uri, status and outcome; client
	 * series also by client name and exception. {@code null} for start times and {@code /actuator} URIs.
	 */
	private static Row key(JsonNode metric) {
		String name = metric.path("__name__").asString("");
		String uri = label(metric, "uri");
		if (uri != null && (uri.equals("/actuator") || uri.startsWith("/actuator/"))) {
			return null;
		}
		boolean client = CLIENT_METRIC.equals(name);
		if (!client && !SERVER_METRIC.equals(name)) {
			return null;
		}
		return new Row(client ? "client" : "server", label(metric, "service"),
				client ? label(metric, "client_name") : null, label(metric, "method"), uri, label(metric, "status"),
				label(metric, "outcome"), client ? label(metric, "exception") : null, 0);
	}

	private static String label(JsonNode metric, String name) {
		JsonNode value = metric.path(name);
		return value.isMissingNode() ? null : value.asString();
	}

	private static List<CounterDelta.Sample> samples(JsonNode result) {
		List<CounterDelta.Sample> samples = new ArrayList<>();
		for (JsonNode point : result.path("values")) {
			samples.add(new CounterDelta.Sample(point.path(0).asLong(), Double.parseDouble(point.path(1).asString())));
		}
		return samples;
	}

	/** Whether the service's process start time differs at the two sample times; unknown counts as not restarted. */
	private static boolean restarted(NavigableMap<Long, Double> startTimes, long before, long after) {
		Map.Entry<Long, Double> was = startTimes.floorEntry(before);
		Map.Entry<Long, Double> is = startTimes.floorEntry(after);
		return was != null && is != null && !Objects.equals(was.getValue(), is.getValue());
	}

	/**
	 * One edge on one side with its exact count. {@code clientName} and {@code exception} are client-side only; a label
	 * the series does not carry is left out.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Row(String side, String service, String clientName, String method, String uri, String status,
			String outcome, String exception, long count) {

		Row withCount(long count) {
			return new Row(this.side, this.service, this.clientName, this.method, this.uri, this.status, this.outcome,
					this.exception, count);
		}

	}

}
