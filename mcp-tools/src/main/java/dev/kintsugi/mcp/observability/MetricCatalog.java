package dev.kintsugi.mcp.observability;

import java.util.Arrays;
import java.util.List;

import dev.kintsugi.mcp.tool.InvalidParameterException;
import dev.kintsugi.mcp.tool.ToolInputs;

/**
 * The allow-list of {@code query_metric}: each name maps to one PromQL template. The metric and label names come from
 * the "Lab — Faults" dashboard queries and the runbooks' "Key signals" sections; every series carries the
 * {@code service} label that Prometheus adds per scrape target.
 *
 * <p>
 * Placeholders: {@code {sel}} is the service matcher, {@code service="<service>"} or a regex over the allowed services
 * only; {@code {w}} is the rate window, the larger of 60 s and the query step, so a wide step does not skip samples.
 */
public enum MetricCatalog {

	REQUEST_RATE("request_rate", "sum by (service, uri) (rate(http_server_requests_seconds_count{{sel}}[{w}]))"),

	/**
	 * Server responses with a 5xx status only: a 4xx refusal or a call the caller gave up on is not counted, so this
	 * rate stays flat when a dependency is slow or refuses with 4xx (T5c8).
	 */
	ERROR_RATE("error_rate",
			"sum by (service, uri, status) (rate(http_server_requests_seconds_count{{sel},status=~\"5..\"}[{w}]))"),

	LATENCY_AVG("latency_avg", "sum by (service, uri) (rate(http_server_requests_seconds_sum{{sel}}[{w}]))"
			+ " / sum by (service, uri) (rate(http_server_requests_seconds_count{{sel}}[{w}]))"),

	LATENCY_MAX("latency_max", "max by (service, uri) (http_server_requests_seconds_max{{sel}})"),

	LAB_ORDERS("lab_orders", "sum by (service, status) (lab_orders{{sel}})"),

	LAB_PAYMENTS("lab_payments", "sum by (service, status) (lab_payments{{sel}})"),

	GATEWAY_AUTHORIZE_INFLIGHT("gateway_authorize_inflight", "sum by (service) (gateway_authorize_inflight{{sel}})"),

	HIKARICP_CONNECTIONS_ACTIVE("hikaricp_connections_active", "sum by (service) (hikaricp_connections_active{{sel}})"),

	HIKARICP_CONNECTIONS_IDLE("hikaricp_connections_idle", "sum by (service) (hikaricp_connections_idle{{sel}})"),

	HIKARICP_CONNECTIONS_PENDING("hikaricp_connections_pending",
			"sum by (service) (hikaricp_connections_pending{{sel}})"),

	HIKARICP_CONNECTIONS_MAX("hikaricp_connections_max", "sum by (service) (hikaricp_connections_max{{sel}})"),

	HIKARICP_CONNECTIONS_TIMEOUT_RATE("hikaricp_connections_timeout_rate",
			"sum by (service) (rate(hikaricp_connections_timeout_total{{sel}}[{w}]))"),

	CLIENT_REQUEST_OUTCOMES("client_request_outcomes", "sum by (service, client_name, outcome, exception)"
			+ " (rate(http_client_requests_seconds_count{{sel}}[{w}]))");

	/**
	 * At most this many points per series: the step is chosen so that the window holds no more. 60 compact points of a
	 * typical series take about 1.5 KB, so several series fit in the 8 KB result.
	 */
	public static final int MAX_POINTS = 60;

	/** Prometheus scrapes every 5 s, so a shorter step adds no information. */
	static final long MIN_STEP_SECONDS = 5;

	static final long MIN_RATE_WINDOW_SECONDS = 60;

	private final String id;

	private final String template;

	MetricCatalog(String id, String template) {
		this.id = id;
		this.template = template;
	}

	public String id() {
		return this.id;
	}

	public String template() {
		return this.template;
	}

	public static List<String> ids() {
		return Arrays.stream(values()).map(MetricCatalog::id).toList();
	}

	public static MetricCatalog byId(String id) {
		return Arrays.stream(values())
			.filter(metric -> metric.id.equals(id))
			.findFirst()
			.orElseThrow(() -> new InvalidParameterException("metric", "must be one of " + ids()));
	}

	/** The PromQL for {@code service} ({@code null} for every allowed service) over a window of {@code sinceMinutes}. */
	public String query(String service, int sinceMinutes) {
		long window = Math.max(MIN_RATE_WINDOW_SECONDS, stepSeconds(sinceMinutes));
		return this.template.replace("{sel}", serviceMatcher(service)).replace("{w}", window + "s");
	}

	/**
	 * The smallest step, at least 5 s, that keeps a window of {@code sinceMinutes} within {@value #MAX_POINTS} points:
	 * a range of {@code w} seconds at step {@code s} holds {@code floor(w / s) + 1} points.
	 */
	static long stepSeconds(int sinceMinutes) {
		long window = sinceMinutes * 60L;
		return Math.max(MIN_STEP_SECONDS, (window + MAX_POINTS - 2) / (MAX_POINTS - 1));
	}

	/** {@code service="<service>"}, or a regex that matches the allowed services and nothing else. */
	static String serviceMatcher(String service) {
		return service != null ? "service=\"" + service + "\""
				: "service=~\"" + String.join("|", ToolInputs.SERVICES) + "\"";
	}

}
