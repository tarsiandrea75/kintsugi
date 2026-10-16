package dev.kintsugi.mcp.observability;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.mcp.config.BackendProperties;
import dev.kintsugi.mcp.tool.ToolCallRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code traffic_counts} against a fake Prometheus: exact per-edge counts from raw samples (mcp-tools spec). */
class TrafficCountsToolTest {

	private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

	private static final long START = NOW.minusSeconds(10 * 60).getEpochSecond();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final FakeHttpBackend loki = FakeHttpBackend.start();

	private final FakeHttpBackend prometheus = FakeHttpBackend.start();

	private final ObservabilityTools tools = new ObservabilityTools(new LokiClient(client(this.loki)),
			new PrometheusClient(client(this.prometheus)), new ToolCallRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

	@AfterEach
	void stopFakes() {
		this.loki.close();
		this.prometheus.close();
	}

	@Test
	void queriesTheRawCountersAndStartTimesOfTheAllowedServicesAtTheScrapeStep() {
		this.tools.trafficCounts(10, null);

		FakeHttpBackend.RecordedRequest request = this.prometheus.lastRequest();
		assertThat(request.path()).isEqualTo("/api/v1/query_range");
		assertThat(request.params()).containsEntry("query", "{__name__=~\"http_server_requests_seconds_count"
				+ "|http_client_requests_seconds_count|process_start_time_seconds\","
				+ "service=~\"order-service|payment-service|inventory-service|gateway-mock\",uri!~\"/actuator(/.*)?\"}")
			.containsEntry("start", String.valueOf(START))
			.containsEntry("end", String.valueOf(NOW.getEpochSecond()))
			.containsEntry("step", "5");
	}

	@Test
	void theBoundedWindowEndsUntilMinutesAgo() {
		this.tools.trafficCounts(20, 10);

		assertThat(this.prometheus.lastRequest().params())
			.containsEntry("start", String.valueOf(NOW.minusSeconds(20 * 60).getEpochSecond()))
			.containsEntry("end", String.valueOf(NOW.minusSeconds(10 * 60).getEpochSecond()));
	}

	@Test
	void returnsExactCountsPerSideAndLabelsSortedWithZeroRowsLeftOut() {
		this.prometheus.replyWith(200, matrix(
				series(server("payment-service", "POST", "/payments", "201", "SUCCESS"), 24, 24, 24),
				series(client("order-service", "payment-service", "POST", "/payments", "CLIENT_ERROR", "UNKNOWN",
						"ConnectException"), 1, 3, 6),
				series(server("gateway-mock", "POST", "/authorize", "200", "SUCCESS"), 10, 12, 15),
				series(server("gateway-mock", "POST", "/authorize", "429", "CLIENT_ERROR"), 2, 2, 4),
				series(server("order-service", "GET", "/actuator/prometheus", "200", "SUCCESS"), 1, 2, 3)));

		CallToolResult result = this.tools.trafficCounts(10, null);

		assertThat(result.isError()).isFalse();
		JsonNode body = body(result);
		assertThat(body.path("outcome").asString()).isEqualTo("OK");
		assertThat(body.path("returned").asInt()).isEqualTo(3);
		JsonNode items = body.path("items");
		assertThat(items.get(0).toString()).isEqualTo("{\"side\":\"client\",\"service\":\"order-service\","
				+ "\"clientName\":\"payment-service\",\"method\":\"POST\",\"uri\":\"/payments\","
				+ "\"status\":\"CLIENT_ERROR\",\"outcome\":\"UNKNOWN\",\"exception\":\"ConnectException\",\"count\":5}");
		assertThat(items.get(1).toString()).isEqualTo("{\"side\":\"server\",\"service\":\"gateway-mock\","
				+ "\"method\":\"POST\",\"uri\":\"/authorize\",\"status\":\"200\",\"outcome\":\"SUCCESS\",\"count\":5}");
		assertThat(items.get(2).path("status").asString()).isEqualTo("429");
		assertThat(items.get(2).path("count").asLong()).isEqualTo(2);
	}

	@Test
	void aRecreatedProcessCountsItsNewSamplesFromZero() {
		// order-service is recreated after the second sample: its counter drops from 14 to 2, and its start time moves.
		this.prometheus.replyWith(200, matrix(
				series(server("order-service", "POST", "/orders", "201", "SUCCESS"), 10, 14, 2, 5),
				series(startTime("order-service"), 900, 900, 1300, 1300)));

		JsonNode items = body(this.tools.trafficCounts(10, null)).path("items");

		assertThat(items).hasSize(1);
		assertThat(items.get(0).path("count").asLong()).isEqualTo(4 + 2 + 3);
	}

	@Test
	void aNewStartTimeCountsTheFirstValueOfTheNewProcessInFullEvenWhenItIsHigher() {
		this.prometheus.replyWith(200, matrix(
				series(server("order-service", "POST", "/orders", "201", "SUCCESS"), 1, 3, 5),
				series(startTime("order-service"), 900, 900, 1300)));

		JsonNode items = body(this.tools.trafficCounts(10, null)).path("items");

		assertThat(items.get(0).path("count").asLong()).isEqualTo(2 + 5);
	}

	@Test
	void seriesThatDifferOnlyInDroppedLabelsAreSummed() {
		Map<String, String> first = server("order-service", "POST", "/orders", "500", "SERVER_ERROR");
		first.put("exception", "IllegalStateException");
		Map<String, String> second = server("order-service", "POST", "/orders", "500", "SERVER_ERROR");
		second.put("exception", "DataAccessException");
		this.prometheus.replyWith(200, matrix(series(first, 0, 1), series(second, 0, 2)));

		JsonNode items = body(this.tools.trafficCounts(10, null)).path("items");

		assertThat(items).hasSize(1);
		assertThat(items.get(0).path("count").asLong()).isEqualTo(3);
		assertThat(items.get(0).has("exception")).isFalse();
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = { "null,null", "0,null", "361,null", "10,-1", "10,10", "10,360" })
	void rejectsAnOutOfDomainWindowBeforeAnyQuery(Integer sinceMinutes, Integer untilMinutesAgo) {
		CallToolResult result = this.tools.trafficCounts(sinceMinutes, untilMinutesAgo);

		assertThat(result.isError()).isTrue();
		assertThat(body(result).path("outcome").asString()).isEqualTo("INVALID_PARAMETER");
		assertThat(this.prometheus.calls()).isZero();
	}

	@Test
	void untilMinutesAgoMustBeLessThanSinceMinutes() {
		JsonNode body = body(this.tools.trafficCounts(15, 15));

		assertThat(body.path("reason").asString()).contains("untilMinutesAgo").contains("less than sinceMinutes");
	}

	@Test
	void aPrometheusFailureIsAnUpstreamError() {
		this.prometheus.replyWith(500, "boom");

		CallToolResult result = this.tools.trafficCounts(10, null);

		assertThat(result.isError()).isTrue();
		assertThat(body(result).path("outcome").asString()).isEqualTo("UPSTREAM_ERROR");
		assertThat(body(result).path("reason").asString()).isEqualTo("Prometheus answered HTTP 500");
	}

	private static Map<String, String> server(String service, String method, String uri, String status,
			String outcome) {
		Map<String, String> labels = labels("http_server_requests_seconds_count", service);
		labels.put("method", method);
		labels.put("uri", uri);
		labels.put("status", status);
		labels.put("outcome", outcome);
		labels.put("exception", "none");
		labels.put("error", "none");
		return labels;
	}

	private static Map<String, String> client(String service, String clientName, String method, String uri,
			String status, String outcome, String exception) {
		Map<String, String> labels = labels("http_client_requests_seconds_count", service);
		labels.put("client_name", clientName);
		labels.put("method", method);
		labels.put("uri", uri);
		labels.put("status", status);
		labels.put("outcome", outcome);
		labels.put("exception", exception);
		labels.put("error", "ResourceAccessException");
		return labels;
	}

	private static Map<String, String> startTime(String service) {
		return labels("process_start_time_seconds", service);
	}

	private static Map<String, String> labels(String name, String service) {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("__name__", name);
		labels.put("instance", service + ":8080");
		labels.put("job", "lab");
		labels.put("service", service);
		return labels;
	}

	/** One series with a sample every 5 s from the window start. */
	private static String series(Map<String, String> labels, double... values) {
		List<String> points = new ArrayList<>();
		for (int i = 0; i < values.length; i++) {
			points.add("[" + (START + 5L * i) + ",\"" + values[i] + "\"]");
		}
		return "{\"metric\":" + JSON.writeValueAsString(labels) + ",\"values\":[" + String.join(",", points) + "]}";
	}

	private static String matrix(String... series) {
		return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[" + String.join(",", series)
				+ "]}}";
	}

	private static org.springframework.web.client.RestClient client(FakeHttpBackend fake) {
		return BackendClients.restClient(new BackendProperties.Backend(URI.create(fake.baseUrl()),
				Duration.ofSeconds(1), Duration.ofSeconds(1)));
	}

	private static JsonNode body(CallToolResult result) {
		return JSON.readTree(((TextContent) result.content().get(0)).text());
	}

}
