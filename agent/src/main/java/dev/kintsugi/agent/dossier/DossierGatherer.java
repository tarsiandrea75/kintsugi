package dev.kintsugi.agent.dossier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.LogLines;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.SourceEvents;
import dev.kintsugi.agent.dossier.Dossier.StatusCount;
import dev.kintsugi.agent.dossier.Dossier.TableStates;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
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

	/** The lab services, in the order of the call path. */
	public static final List<String> SERVICES = List.of("order-service", "payment-service", "inventory-service",
			"gateway-mock");

	/** The {@code status_summary} tables with states over a window. */
	public static final List<String> STATUS_TABLES = List.of("orders", "payments");

	/** The {@code event_counts} sources. */
	public static final List<String> EVENT_SOURCES = List.of("order", "payment", "reservation");

	/** The log levels read: the lab services log failures at WARN; ERROR is read too, as ADR-011 names both. */
	public static final List<String> LOG_LEVELS = List.of("WARN", "ERROR");

	/** The lines {@code search_logs} is asked for: the cap {@code LabTools} applies to the tool. */
	static final int LOG_LINES = 20;

	private static final String TRUNCATED_SUFFIX = "\n[truncated]";

	private static final Logger log = LoggerFactory.getLogger(DossierGatherer.class);

	private static final JsonMapper JSON = JsonMapper.shared();

	private final LabTools tools;

	private final Clock clock;

	public DossierGatherer(LabTools tools, Clock clock) {
		this.tools = tools;
		this.clock = clock;
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
				result -> services(result, at));
		List<TableStates> states = new ArrayList<>();
		for (String table : STATUS_TABLES) {
			states.add(new TableStates(table, calls.compared("status_summary", "table", table, previous, incident,
					DossierGatherer::statusCounts)));
		}
		Compared<List<TrafficRow>> traffic = calls.compared("traffic_counts", null, null, previous, incident,
				DossierGatherer::trafficRows);
		List<ServiceLogs> logs = new ArrayList<>();
		for (String service : SERVICES) {
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
		for (String source : EVENT_SOURCES) {
			events.add(new SourceEvents(source, calls.compared("event_counts", "source", source, previous, incident,
					DossierGatherer::eventCounts)));
		}
		return new Dossier(at, incident, previous, services, states, traffic, logs, events, calls.made);
	}

	/** {@code sinceMinutes}, and {@code untilMinutesAgo} when the window does not end now. */
	private static ObjectNode args(Window window) {
		ObjectNode args = JSON.createObjectNode().put("sinceMinutes", window.sinceMinutes());
		if (window.untilMinutesAgo() > 0) {
			args.put("untilMinutesAgo", window.untilMinutesAgo());
		}
		return args;
	}

	private static List<ServiceStatus> services(JsonNode result, Instant at) {
		List<ServiceStatus> services = new ArrayList<>();
		for (String service : SERVICES) {
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
