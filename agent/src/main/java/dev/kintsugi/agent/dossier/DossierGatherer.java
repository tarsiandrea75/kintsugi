package dev.kintsugi.agent.dossier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.EventCounts;
import dev.kintsugi.agent.dossier.Dossier.EventGroup;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.LogLines;
import dev.kintsugi.agent.dossier.Dossier.MetricSeries;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceResources;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceLatency;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.SourceEvents;
import dev.kintsugi.agent.dossier.Dossier.StatusCount;
import dev.kintsugi.agent.dossier.Dossier.TableStates;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.dossier.Dossier.UriLatency;
import dev.kintsugi.agent.dossier.Dossier.Window;
import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.lab.ToolResult;

/**
 * Gathers the {@link Dossier} of an incident through {@link LabTools}, the agent's one way into the lab (agent spec,
 * "Evidence dossier gathering"; ADR-011). It makes the same calls, with the same arguments, in the same order for every
 * incident: nothing depends on the symptom or on any tool's output. The calls are code's, not the model's: they do
 * not pass the loop's checks and do not count against its tool budget. A failed call becomes a fact in its section;
 * the gathering never stops early.
 */
public final class DossierGatherer {

	/** The log levels read: the lab services log failures at WARN; ERROR is read too, as ADR-011 names both. */
	public static final List<String> LOG_LEVELS = List.of("WARN", "ERROR");

	/** The {@code query_metric} metric of the latency section: server-side seconds per request, by service and uri. */
	public static final String LATENCY_METRIC = "latency_avg";

	/**
	 * The {@code query_metric} metrics of the resources section, in the order gathered and written (ADR-021, "The
	 * metrics"): the processor, the memory and the open files of each service, in its source's units.
	 */
	public static final List<String> RESOURCE_METRICS = List.of("processor_usage", "memory_used", "open_files");

	/** The lines {@code search_logs} is asked for: the cap {@code LabTools} applies to the tool. */
	static final int LOG_LINES = 20;

	private static final String TRUNCATED_SUFFIX = "\n[truncated]";

	private static final Logger log = LoggerFactory.getLogger(DossierGatherer.class);

	private static final JsonMapper JSON = JsonMapper.shared();

	private final LabTools tools;

	private final Clock clock;

	/** The services, in the order of the call path ({@link Topology#gatherOrder()}). */
	private final List<String> services;

	/** The {@code status_summary} tables with states over a window ({@link Topology#statusTables()}). */
	private final List<String> statusTables;

	/** The {@code event_counts} sources ({@link Topology#eventSources()}). */
	private final List<String> eventSources;

	/**
	 * A gatherer that reads, through {@code tools}, the services, tables and sources of {@code topology}
	 * (odd/tasks/topology-data.md T4): the services down the call path, the tables and sources in file order.
	 */
	public DossierGatherer(LabTools tools, Clock clock, Topology topology) {
		this.tools = tools;
		this.clock = clock;
		this.services = topology.gatherOrder();
		this.statusTables = topology.statusTables();
		this.eventSources = topology.eventSources();
	}

	/**
	 * The dossier for an incident window of the last {@code sinceMinutes} minutes, compared, where a section compares,
	 * with the {@code sinceMinutes} minutes just before. A previous window longer than the tools accept (360 minutes)
	 * is still asked for, and their rejection recorded.
	 * @throws IllegalArgumentException if {@code sinceMinutes} is below 1
	 */
	public Dossier gather(int sinceMinutes) {
		if (sinceMinutes < 1) {
			throw new IllegalArgumentException("sinceMinutes must be at least 1, was " + sinceMinutes);
		}
		Instant at = this.clock.instant();
		Duration n = Duration.ofMinutes(sinceMinutes);
		Window incident = new Window(sinceMinutes, 0, at.minus(n), at);
		Window previous = new Window(2 * sinceMinutes, sinceMinutes, at.minus(n.multipliedBy(2)), at.minus(n));
		Calls calls = new Calls();

		Gathered<List<ServiceStatus>> services = calls.read("service_restarts", args(incident),
				result -> services(result, at, this.services));
		List<TableStates> states = new ArrayList<>();
		for (String table : this.statusTables) {
			states.add(new TableStates(table, calls.compared("status_summary", "table", table, previous, incident,
					DossierGatherer::statusCounts)));
		}
		Compared<List<TrafficRow>> traffic = calls.compared("traffic_counts", null, null, previous, incident,
				DossierGatherer::trafficRows);
		// query_metric takes no untilMinutesAgo: one call per service over both windows, its points split by time.
		List<ServiceLatency> latency = new ArrayList<>();
		for (String service : this.services) {
			ObjectNode args = JSON.createObjectNode()
				.put("metric", LATENCY_METRIC)
				.put("service", service)
				.put("sinceMinutes", previous.sinceMinutes());
			latency.add(new ServiceLatency(service,
					calls.read("query_metric", args, result -> latency(result, incident.from()))));
		}
		// The resources, after the latency and with its arguments: one call per service and metric (ADR-021).
		List<ServiceResources> resources = new ArrayList<>();
		for (String service : this.services) {
			List<MetricSeries> metrics = new ArrayList<>();
			for (String metric : RESOURCE_METRICS) {
				ObjectNode args = JSON.createObjectNode()
					.put("metric", metric)
					.put("service", service)
					.put("sinceMinutes", previous.sinceMinutes());
				metrics.add(new MetricSeries(metric,
						calls.read("query_metric", args, result -> resource(result, incident.from()))));
			}
			resources.add(new ServiceResources(service, metrics));
		}
		List<ServiceLogs> logs = new ArrayList<>();
		for (String service : this.services) {
			List<LevelLogs> levels = new ArrayList<>();
			for (String level : LOG_LEVELS) {
				ObjectNode args = JSON.createObjectNode().put("service", service).put("level", level);
				args.setAll(args(incident));
				Gathered<Long> count = calls.read("count_logs", args, DossierGatherer::logCount);
				Gathered<LogLines> lines = calls.read("search_logs", args.deepCopy().put("limit", LOG_LINES),
						DossierGatherer::logLines);
				levels.add(new LevelLogs(level, count, lines));
			}
			logs.add(new ServiceLogs(service, levels));
		}
		List<SourceEvents> events = new ArrayList<>();
		for (String source : this.eventSources) {
			events.add(new SourceEvents(source, calls.compared("event_counts", "source", source, previous, incident,
					DossierGatherer::eventCounts)));
		}
		return new Dossier(at, incident, previous, services, states, traffic, latency, resources, logs, events,
				calls.made);
	}

	/** {@code sinceMinutes}, and {@code untilMinutesAgo} when the window does not end now. */
	private static ObjectNode args(Window window) {
		ObjectNode args = JSON.createObjectNode().put("sinceMinutes", window.sinceMinutes());
		if (window.untilMinutesAgo() > 0) {
			args.put("untilMinutesAgo", window.untilMinutesAgo());
		}
		return args;
	}

	private static List<ServiceStatus> services(JsonNode result, Instant at, List<String> names) {
		List<ServiceStatus> services = new ArrayList<>();
		for (String service : names) {
			JsonNode item = null;
			for (JsonNode candidate : result.path("items")) {
				if (service.equals(candidate.path("service").asString(""))) {
					item = candidate;
				}
			}
			if (item == null) {
				services.add(new ServiceStatus(service, List.of(), List.of(), null));
				continue;
			}
			List<Instant> startTimes = new ArrayList<>();
			for (JsonNode time : item.path("startTimes")) {
				startTimes.add(Instant.parse(time.asString()));
			}
			// Oldest first, so the restarts in the window are the newest entries whatever the order of the output.
			startTimes.sort(null);
			int restarts = (int) Math.min(number(item, "restartsInWindow"), startTimes.size());
			Duration uptime = startTimes.isEmpty() ? null
					: Duration.between(startTimes.stream().max(Instant::compareTo).orElseThrow(), at);
			services.add(new ServiceStatus(service, startTimes,
					startTimes.subList(startTimes.size() - restarts, startTimes.size()), uptime));
		}
		return services;
	}

	private static List<StatusCount> statusCounts(JsonNode result) {
		List<StatusCount> counts = new ArrayList<>();
		for (JsonNode item : result.path("items")) {
			List<String> samples = new ArrayList<>();
			item.path("sampleOrderIds").forEach(id -> samples.add(id.asString()));
			counts.add(new StatusCount(text(item, "status"), number(item, "count"), samples));
		}
		return counts;
	}

	private static List<TrafficRow> trafficRows(JsonNode result) {
		List<TrafficRow> rows = new ArrayList<>();
		for (JsonNode item : result.path("items")) {
			rows.add(new TrafficRow(text(item, "side"), text(item, "service"), text(item, "clientName"),
					text(item, "method"), text(item, "uri"), text(item, "status"), text(item, "outcome"),
					text(item, "exception"), number(item, "count")));
		}
		return rows;
	}

	/** The series, each point before {@code incidentFrom} in the previous window and the others in the incident one. */
	private static LatencySeries latency(JsonNode result, Instant incidentFrom) {
		List<UriLatency> uris = new ArrayList<>();
		for (JsonNode item : result.path("items")) {
			List<LatencyPoints> windows = windows(item, incidentFrom);
			uris.add(new UriLatency(text(item.path("labels"), "uri"), windows.get(0), windows.get(1)));
		}
		return new LatencySeries(result.path("truncated").asBoolean(false), uris);
	}

	/**
	 * The one series of a resource metric, split as {@link #latency} splits a series; none gives two windows without
	 * points, and more than one makes the output unreadable, since a resource series is labelled by its service only.
	 */
	private static ResourceSeries resource(JsonNode result, Instant incidentFrom) {
		JsonNode items = result.path("items");
		if (items.size() > 1) {
			throw new IllegalArgumentException(items.size() + " series, not one");
		}
		boolean truncated = result.path("truncated").asBoolean(false);
		if (items.isEmpty()) {
			return new ResourceSeries(truncated, points(List.of()), points(List.of()));
		}
		List<LatencyPoints> windows = windows(items.get(0), incidentFrom);
		return new ResourceSeries(truncated, windows.get(0), windows.get(1));
	}

	/** The previous window's and the incident window's points of one series: before {@code incidentFrom}, then not. */
	private static List<LatencyPoints> windows(JsonNode item, Instant incidentFrom) {
		List<Double> previous = new ArrayList<>();
		List<Double> incident = new ArrayList<>();
		for (JsonNode point : item.path("points")) {
			Instant time = Instant.ofEpochSecond(number(point, 0));
			(time.isBefore(incidentFrom) ? previous : incident).add(seconds(point.path(1)));
		}
		return List.of(points(previous), points(incident));
	}

	/** A point's value as Prometheus writes it; {@code null} for {@code NaN} and the infinities, which are no value. */
	private static Double seconds(JsonNode value) {
		if (!value.isString()) {
			throw new IllegalArgumentException("a point's value is not a string");
		}
		String text = value.asString();
		if (text.equals("NaN") || text.equals("+Inf") || text.equals("-Inf")) {
			return null;
		}
		double seconds = Double.parseDouble(text);
		return Double.isFinite(seconds) ? seconds : null;
	}

	private static LatencyPoints points(List<Double> points) {
		List<Double> values = points.stream().filter(Objects::nonNull).toList();
		if (values.isEmpty()) {
			return new LatencyPoints(points.size(), 0, null, null);
		}
		double sum = 0;
		double max = values.get(0);
		for (double value : values) {
			sum += value;
			max = Math.max(max, value);
		}
		return new LatencyPoints(points.size(), values.size(), sum / values.size(), max);
	}

	private static Long logCount(JsonNode result) {
		return number(result.path("items").path(0), "count");
	}

	private static LogLines logLines(JsonNode result) {
		JsonNode items = result.path("items");
		return new LogLines(items.size(), result.path("truncated").asBoolean(false), LogSignatures.of(items));
	}

	private static EventCounts eventCounts(JsonNode result) {
		JsonNode item = result.path("items").path(0);
		List<EventGroup> groups = new ArrayList<>();
		for (JsonNode group : item.path("groups")) {
			groups.add(new EventGroup(text(group, "type"), text(group, "outcome"), number(group, "count")));
		}
		return new EventCounts(number(item, "total"), groups);
	}

	/** An integer field; anything else makes the output unreadable. */
	private static long number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isIntegralNumber()) {
			throw new IllegalArgumentException("'" + field + "' is not an integer");
		}
		return value.longValue();
	}

	/** An integer element of an array; anything else makes the output unreadable. */
	private static long number(JsonNode array, int index) {
		JsonNode value = array.get(index);
		if (value == null || !value.isIntegralNumber()) {
			throw new IllegalArgumentException("element " + index + " is not an integer");
		}
		return value.longValue();
	}

	/** A string field, or {@code null} when the output leaves it out. */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	/** The calls of one gathering, in the order made. */
	private final class Calls {

		private final List<ToolResult> made = new ArrayList<>();

		<T> Compared<T> compared(String tool, String subjectName, String subject, Window previous, Window incident,
				Function<JsonNode, T> facts) {
			return new Compared<>(read(tool, windowArgs(subjectName, subject, previous), facts),
					read(tool, windowArgs(subjectName, subject, incident), facts));
		}

		<T> Gathered<T> read(String tool, ObjectNode args, Function<JsonNode, T> facts) {
			ToolResult result = call(tool, JSON.writeValueAsString(args));
			this.made.add(result);
			if (result.outcome() == ToolOutcome.CLIENT_ERROR) {
				return new Gathered.Failed<>(tool, result.outcome(), result.rawOutput());
			}
			JsonNode output;
			try {
				output = parse(result.rawOutput());
			}
			catch (JacksonException | IllegalArgumentException unreadable) {
				return new Gathered.Failed<>(tool, result.outcome(), result.outcome() == ToolOutcome.OK
						? "the output could not be read: it is not a JSON object" : result.rawOutput());
			}
			if (result.outcome() != ToolOutcome.OK) {
				return new Gathered.Failed<>(tool, result.outcome(),
						output.path("reason").asString(result.rawOutput()));
			}
			try {
				return new Gathered.Ok<>(facts.apply(output));
			}
			catch (RuntimeException unreadable) {
				return new Gathered.Failed<>(tool, result.outcome(),
						"the output could not be read: " + unreadable.getMessage());
			}
		}

		private static ObjectNode windowArgs(String subjectName, String subject, Window window) {
			ObjectNode args = JSON.createObjectNode();
			if (subjectName != null) {
				args.put(subjectName, subject);
			}
			return args.setAll(args(window));
		}

		/** One call; a call that throws, which {@link LabTools} should never do, is recorded as a client error. */
		private ToolResult call(String tool, String args) {
			try {
				return DossierGatherer.this.tools.call(tool, args);
			}
			catch (RuntimeException failed) {
				String reason = "the call failed: " + failed.getClass().getSimpleName() + ": " + failed.getMessage();
				log.warn("event=dossier_call_failed tool={} reason=\"{}\"", tool, reason);
				return new ToolResult(tool, args, reason, ToolOutcome.CLIENT_ERROR, 0);
			}
		}

		private static JsonNode parse(String rawOutput) {
			String json = rawOutput.endsWith(TRUNCATED_SUFFIX)
					? rawOutput.substring(0, rawOutput.length() - TRUNCATED_SUFFIX.length()) : rawOutput;
			JsonNode node = JSON.readTree(json);
			if (node == null || !node.isObject()) {
				throw new IllegalArgumentException("not a JSON object");
			}
			return node;
		}

	}

}
