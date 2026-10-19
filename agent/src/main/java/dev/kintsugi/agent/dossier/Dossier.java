package dev.kintsugi.agent.dossier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import dev.kintsugi.agent.lab.ToolResult;

/**
 * The evidence dossier of one incident: facts read through the mcp-tools tools for the incident window and the
 * previous one, in ADR-011's five sections and the latency added by the dossier-latency feature. It holds no
 * judgment, threshold or marker; the only values computed here are arithmetic facts (window bounds, uptime, the mean
 * and maximum of latency points) and the grouping of log lines by signature. Lists keep a fixed order, so the dossier
 * has the same shape for every incident.
 *
 * @param gatheredAt the time the gathering started, by the gatherer's clock
 * @param incidentWindow the last N minutes
 * @param previousWindow the N minutes just before the incident window
 * @param services service status per lab service, in {@link DossierGatherer#SERVICES} order
 * @param states order and payment states, in {@link DossierGatherer#STATUS_TABLES} order
 * @param traffic the request counts per edge and side
 * @param latency the server-side latency per service, in {@link DossierGatherer#SERVICES} order
 * @param logs WARN and ERROR log signatures per service, incident window only
 * @param events event counts per source, in {@link DossierGatherer#EVENT_SOURCES} order
 * @param calls every tool call made, in order, with its full output; how they are recorded as steps is the loop's
 * concern
 */
public record Dossier(Instant gatheredAt, Window incidentWindow, Window previousWindow,
		Gathered<List<ServiceStatus>> services, List<TableStates> states, Compared<List<TrafficRow>> traffic,
		List<ServiceLatency> latency, List<ServiceLogs> logs, List<SourceEvents> events, List<ToolResult> calls) {

	public Dossier {
		states = List.copyOf(states);
		latency = List.copyOf(latency);
		logs = List.copyOf(logs);
		events = List.copyOf(events);
		calls = List.copyOf(calls);
	}

	/**
	 * A window as the tools take it, {@code [now - sinceMinutes, now - untilMinutesAgo)}, and its bounds by the
	 * gatherer's clock. mcp-tools reads its own clock, so the bounds it used may differ by the time the calls took.
	 */
	public record Window(int sinceMinutes, int untilMinutesAgo, Instant from, Instant to) {
	}

	/** The same facts for the previous window and for the incident window. */
	public record Compared<T>(Gathered<T> previous, Gathered<T> incident) {
	}

	/**
	 * One lab service, from {@code service_restarts} over the incident window.
	 *
	 * @param startTimes the process start times seen in the window, oldest first
	 * @param restartsInWindow the start times inside the window, oldest first
	 * @param uptime {@code gatheredAt} minus the newest start time; {@code null} when no start time was seen
	 */
	public record ServiceStatus(String service, List<Instant> startTimes, List<Instant> restartsInWindow,
			Duration uptime) {

		public ServiceStatus {
			startTimes = List.copyOf(startTimes);
			restartsInWindow = List.copyOf(restartsInWindow);
		}

	}

	/** One table of {@code status_summary}: {@code orders} or {@code payments}. */
	public record TableStates(String table, Compared<List<StatusCount>> windows) {
	}

	/** One status with its count and up to 3 order ids, newest first, as {@code status_summary} gives them. */
	public record StatusCount(String status, long count, List<String> sampleOrderIds) {

		public StatusCount {
			sampleOrderIds = List.copyOf(sampleOrderIds);
		}

	}

	/**
	 * One row of {@code traffic_counts}, labels as the tool gives them; a label the row does not carry is
	 * {@code null}.
	 */
	public record TrafficRow(String side, String service, String clientName, String method, String uri,
			String status, String outcome, String exception, long count) {
	}

	/**
	 * One service's {@code query_metric latency_avg} output: one call over both windows, since the tool takes no
	 * {@code untilMinutesAgo}.
	 */
	public record ServiceLatency(String service, Gathered<LatencySeries> latency) {
	}

	/**
	 * The series of one {@code latency_avg} output, in the tool's order.
	 *
	 * @param truncated whether the tool left out series or points to fit its output cap
	 */
	public record LatencySeries(boolean truncated, List<UriLatency> uris) {

		public LatencySeries {
			uris = List.copyOf(uris);
		}

	}

	/**
	 * One series, labelled by its {@code uri} ({@code null} when the series has none), its points split at the start
	 * of the incident window: earlier points belong to the previous window, the others to the incident window.
	 */
	public record UriLatency(String uri, LatencyPoints previous, LatencyPoints incident) {
	}

	/**
	 * The points of one series in one window. Each point is the average server-side answer time, in seconds, of the
	 * requests that ended in its rate window; a point has no value ({@code NaN}) when none ended.
	 *
	 * @param points the points in the window
	 * @param values the points with a finite value
	 * @param mean the mean of those values; {@code null} when there is none
	 * @param max the largest of those values; {@code null} when there is none
	 */
	public record LatencyPoints(int points, int values, Double mean, Double max) {
	}

	/** One service's logs, a {@link LevelLogs} per level in {@link DossierGatherer#LOG_LEVELS} order. */
	public record ServiceLogs(String service, List<LevelLogs> levels) {

		public ServiceLogs {
			levels = List.copyOf(levels);
		}

	}

	/**
	 * One level of one service: the exact line count from {@code count_logs}, and the signatures of the newest lines
	 * {@code search_logs} returned.
	 */
	public record LevelLogs(String level, Gathered<Long> count, Gathered<LogLines> lines) {
	}

	/**
	 * The lines {@code search_logs} returned, grouped by signature.
	 *
	 * @param returned the number of lines returned
	 * @param truncated whether the tool returned fewer lines than the window holds
	 * @param signatures the groups, most frequent first, then newest first
	 */
	public record LogLines(int returned, boolean truncated, List<LogSignature> signatures) {

		public LogLines {
			signatures = List.copyOf(signatures);
		}

	}

	/**
	 * Log messages alike but for numbers and ids.
	 *
	 * @param count how many returned lines have this signature
	 * @param newest the newest line's timestamp; {@code null} when no line had a readable one
	 * @param message the newest line's message, the text after the logger
	 */
	public record LogSignature(int count, Instant newest, UntrustedText message) {
	}

	/** One event source of {@code event_counts}: {@code order}, {@code payment} or {@code reservation}. */
	public record SourceEvents(String source, Compared<EventCounts> windows) {
	}

	/** The events of one window: the total and the count per type and outcome, in the tool's order. */
	public record EventCounts(long total, List<EventGroup> groups) {

		public EventCounts {
			groups = List.copyOf(groups);
		}

	}

	/** One (type, outcome) pair; {@code outcome} is {@code null} when the events have none. */
	public record EventGroup(String type, String outcome, long count) {
	}

}
