package dev.kintsugi.agent.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.kintsugi.agent.dossier.CallGraph;
import dev.kintsugi.agent.dossier.CallGraph.Edge;
import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.dossier.Dossier.UriLatency;
import dev.kintsugi.agent.dossier.Gathered;

/**
 * Config 0: a rules-only diagnosis over the typed dossier and the call graph, exactly as ADR-016 states it. It runs the
 * rules the prompt gives the model, in their order (R-REACH, R-DOWN, R-NONE), so the paired difference between a model
 * route and this baseline measures what the model adds over its own prompt.
 * <p>
 * It is a pure function of the record: no Spring, no I/O, no clock, no model, and never the rendered text, so the
 * dossier's view cannot change its answer. It is a baseline, never the agent's answer.
 */
public final class RulesDiagnosis {

	/** The F3 rule: a connection failure to a healthy callee is a configuration of the caller. */
	public static final String REACH = "R-REACH";

	/** The F1 and F2 rule: follow the calls down to the last slow or refusing component. */
	public static final String DOWN = "R-DOWN";

	/** No rule fired. */
	public static final String NONE = "R-NONE";

	static final String UNKNOWN = "UNKNOWN";

	static final String LATENCY = "DEPENDENCY_LATENCY";

	static final String REJECTING = "DEPENDENCY_REJECTING";

	static final String CONFIGURATION = "CONFIGURATION";

	/** Slow by latency: an incident mean at least this many times the previous mean... */
	static final double SLOW_RATIO = 2.0;

	/** ...and at least this many seconds. */
	static final double SLOW_SECONDS = 0.5;

	/** Slow by timeouts: at least this many timeouts in the callers' client rows. */
	static final long SLOW_TIMEOUTS = 3;

	/** Refusing: at least this many error answers... */
	static final long REFUSING_ANSWERS = 3;

	/** ...making up at least this share of the answers. */
	static final double REFUSING_SHARE = 0.20;

	/** The exception class names of a connection failure, besides any name containing {@code Connection}. */
	private static final Set<String> CONNECTION_EXCEPTIONS = Set.of("ConnectException", "UnknownHostException",
			"ClosedChannelException", "NoRouteToHostException");

	private static final String ACTUATOR = "/actuator";

	private static final LatencySeries NO_LATENCY = new LatencySeries(false, List.of());

	/** Config H's cap on the candidates (ADR-019). */
	static final int MAX_CANDIDATES = 5;

	/** The walk's order among abnormal services: the larger refusing share, then the larger ratio, then services order. */
	private static final Comparator<Assessment> BY_ABNORMALITY = Comparator
		.comparingDouble((Assessment a) -> a.refusing() ? -a.share() : 0)
		.thenComparingDouble(a -> -a.ratio())
		.thenComparing(a -> order(a.service()));

	private final Dossier dossier;

	private final List<TrafficRow> rows;

	private final Map<String, Assessment> assessments = new HashMap<>();

	private RulesDiagnosis(Dossier dossier) {
		this.dossier = dossier;
		this.rows = ok(dossier.traffic().incident(), List.<TrafficRow>of())
			.stream()
			.filter(row -> !actuator(row.uri()))
			.toList();
	}

	/** The verdict of the first rule that fires on {@code dossier}. */
	public static RulesVerdict diagnose(Dossier dossier) {
		return new RulesDiagnosis(dossier).verdict();
	}

	/**
	 * Config H's proposals on {@code dossier} (ADR-019), 0 to {@value #MAX_CANDIDATES}, no service twice, in this order:
	 * {@link #diagnose}'s verdict when it is not UNKNOWN; the other R-REACH callers, by failure count then services
	 * order; the other abnormal (slow or refusing) services, anywhere on the call graph, in the walk's order. Each keeps
	 * the category of the rule that would name it. {@link #diagnose} itself is unchanged.
	 */
	public static List<RulesCandidate> candidates(Dossier dossier) {
		RulesDiagnosis diagnosis = new RulesDiagnosis(dossier);
		Map<String, RulesCandidate> candidates = new LinkedHashMap<>();
		RulesVerdict verdict = diagnosis.verdict();
		if (!UNKNOWN.equals(verdict.service())) {
			candidates.put(verdict.service(), candidate(verdict));
		}
		diagnosis.reachCallers().forEach(caller -> candidates.putIfAbsent(caller.service(), candidate(caller)));
		CallGraph.SERVICES.stream()
			.map(diagnosis::assess)
			.filter(Assessment::abnormal)
			.sorted(BY_ABNORMALITY)
			.forEach(a -> candidates.putIfAbsent(a.service(),
					new RulesCandidate(a.service(), a.refusing() ? REJECTING : LATENCY, DOWN, a.abnormalFacts())));
		return candidates.values().stream().limit(MAX_CANDIDATES).toList();
	}

	private static RulesCandidate candidate(RulesVerdict verdict) {
		return new RulesCandidate(verdict.service(), verdict.category(), verdict.rule(), verdict.facts());
	}

	/** The verdict of the first rule that fires. */
	private RulesVerdict verdict() {
		RulesVerdict reach = reach();
		if (reach != null) {
			return reach;
		}
		RulesVerdict down = down();
		if (down != null) {
			return down;
		}
		return new RulesVerdict(UNKNOWN, UNKNOWN, NONE,
				List.of("no connection failure to a healthy callee in the incident window",
						"no slow or refusing component on the call path from "
								+ String.join(", ", entries())));
	}

	/** R-REACH: the caller with the most connection failures to healthy callees, ties in services order. */
	private RulesVerdict reach() {
		List<RulesVerdict> callers = reachCallers();
		return callers.isEmpty() ? null : callers.get(0);
	}

	/** Every caller R-REACH could name, the most connection failures to healthy callees first, ties in services order. */
	private List<RulesVerdict> reachCallers() {
		Map<String, Long> failures = new LinkedHashMap<>();
		Map<String, List<String>> facts = new HashMap<>();
		for (Edge edge : CallGraph.EDGES) {
			if (edge.caller() == null) {
				continue;
			}
			List<TrafficRow> failed = this.rows.stream()
				.filter(edge::callerRow)
				.filter(row -> connectionFailure(row.exception()))
				.toList();
			if (failed.isEmpty()) {
				continue;
			}
			Assessment callee = assess(edge.target());
			if (!callee.healthy()) {
				continue;
			}
			failures.merge(edge.caller(), sum(failed), Long::sum);
			List<String> callerFacts = facts.computeIfAbsent(edge.caller(), caller -> new ArrayList<>());
			failed.forEach(row -> callerFacts.add(row(row)));
			callerFacts.add(callee.healthFact());
		}
		return failures.keySet()
			.stream()
			.sorted(Comparator.comparing((String caller) -> -failures.get(caller)).thenComparing(RulesDiagnosis::order))
			.map(caller -> new RulesVerdict(caller, CONFIGURATION, REACH, facts.get(caller)))
			.toList();
	}

	/** R-DOWN: from each entry service, down while the callee is slow or refusing; the last one reached. */
	private RulesVerdict down() {
		for (String entry : entries()) {
			Assessment last = assess(entry).abnormal() ? assess(entry) : null;
			List<String> facts = new ArrayList<>(last == null ? List.of() : last.abnormalFacts());
			Set<String> visited = new HashSet<>(List.of(entry));
			String current = entry;
			while (true) {
				Assessment next = CallGraph.calls(current)
					.stream()
					.filter(callee -> !visited.contains(callee))
					.map(this::assess)
					.filter(Assessment::abnormal)
					.min(BY_ABNORMALITY)
					.orElse(null);
				if (next == null) {
					break;
				}
				visited.add(next.service());
				facts.addAll(next.abnormalFacts());
				last = next;
				current = next.service();
			}
			if (last != null) {
				return new RulesVerdict(last.service(), last.refusing() ? REJECTING : LATENCY, DOWN, facts);
			}
		}
		return null;
	}

	/** The services the external client calls, in services order. */
	private static List<String> entries() {
		return CallGraph.SERVICES.stream()
			.filter(service -> CallGraph.calledBy(service).contains(CallGraph.EXTERNAL_CLIENT))
			.toList();
	}

	private Assessment assess(String service) {
		return this.assessments.computeIfAbsent(service, this::assessed);
	}

	private Assessment assessed(String service) {
		List<String> slowFacts = new ArrayList<>();
		double ratio = 0;
		for (UriLatency uri : uris(service)) {
			Double previous = mean(uri.previous());
			Double incident = mean(uri.incident());
			if (previous == null || previous <= 0 || incident == null) {
				continue;
			}
			ratio = Math.max(ratio, incident / previous);
			if (incident >= SLOW_RATIO * previous && incident >= SLOW_SECONDS) {
				slowFacts.add(String.format(Locale.ROOT,
						"%s latency %s: incident mean %.3f s, at least %.0fx the previous mean %.3f s and %.1f s",
						service, uri.uri(), incident, SLOW_RATIO, previous, SLOW_SECONDS));
			}
		}
		List<TrafficRow> toIt = clientRowsTo(service);
		long timeouts = sum(toIt.stream().filter(row -> timeout(row.exception())).toList());
		if (timeouts >= SLOW_TIMEOUTS) {
			slowFacts.add(service + ": " + timeouts + " timeouts in its callers' client rows in the incident window");
		}

		List<TrafficRow> server = this.rows.stream()
			.filter(row -> "server".equals(row.side()) && service.equals(row.service()))
			.toList();
		List<TrafficRow> answers = server.isEmpty()
			? toIt.stream().filter(row -> !connectionFailure(row.exception())).toList()
			: server;
		long total = sum(answers);
		long errors = sum(answers.stream().filter(RulesDiagnosis::errorAnswer).toList());
		double share = total == 0 ? 0 : (double) errors / total;
		boolean refusing = errors >= REFUSING_ANSWERS && errors >= REFUSING_SHARE * total;
		String answerFact = String.format(Locale.ROOT, "%s: %d of %d answers have a status of 400 or more (%s)",
				service, errors, total, server.isEmpty() ? "its callers' client rows" : "its server rows");

		boolean restarted = restarted(service);
		long logLines = logLines(service);
		return new Assessment(service, slowFacts, refusing, share, ratio, answerFact, restarted, logLines);
	}

	private List<UriLatency> uris(String service) {
		return this.dossier.latency()
			.stream()
			.filter(latency -> latency.service().equals(service))
			.map(latency -> ok(latency.latency(), NO_LATENCY))
			.flatMap(series -> series.uris().stream())
			.filter(uri -> !actuator(uri.uri()))
			.toList();
	}

	/** The callers' client rows to {@code service} on the call graph's edges, in the incident window. */
	private List<TrafficRow> clientRowsTo(String service) {
		return CallGraph.EDGES.stream()
			.filter(edge -> edge.caller() != null && edge.target().equals(service))
			.flatMap(edge -> this.rows.stream().filter(edge::callerRow))
			.toList();
	}

	private boolean restarted(String service) {
		return ok(this.dossier.services(), List.<ServiceStatus>of()).stream()
			.anyMatch(status -> status.service().equals(service) && !status.restartsInWindow().isEmpty());
	}

	/** The WARN and ERROR lines counted in the incident window; a failed count is no evidence. */
	private long logLines(String service) {
		return this.dossier.logs()
			.stream()
			.filter(logs -> logs.service().equals(service))
			.map(ServiceLogs::levels)
			.flatMap(List::stream)
			.map(LevelLogs::count)
			.mapToLong(count -> ok(count, 0L))
			.sum();
	}

	private static boolean errorAnswer(TrafficRow row) {
		if (timeout(row.exception()) || row.status() == null || !row.status().matches("\\d{1,4}")) {
			return false;
		}
		return Integer.parseInt(row.status()) >= 400;
	}

	static boolean timeout(String exception) {
		return exception != null && simpleName(exception).contains("Timeout");
	}

	static boolean connectionFailure(String exception) {
		if (exception == null || timeout(exception)) {
			return false;
		}
		String name = simpleName(exception);
		return CONNECTION_EXCEPTIONS.contains(name) || name.contains("Connection");
	}

	private static String simpleName(String exception) {
		return exception.substring(exception.lastIndexOf('.') + 1);
	}

	private static boolean actuator(String uri) {
		return uri != null && (uri.equals(ACTUATOR) || uri.startsWith(ACTUATOR + "/"));
	}

	private static Double mean(LatencyPoints points) {
		return points == null ? null : points.mean();
	}

	private static long sum(List<TrafficRow> rows) {
		return rows.stream().mapToLong(TrafficRow::count).sum();
	}

	private static int order(String service) {
		int index = CallGraph.SERVICES.indexOf(service);
		return index < 0 ? Integer.MAX_VALUE : index;
	}

	private static <T> T ok(Gathered<T> gathered, T none) {
		return gathered instanceof Gathered.Ok<T> ok ? ok.value() : none;
	}

	private static String row(TrafficRow row) {
		return row.side() + " " + row.service() + " -> " + row.clientName() + " " + row.method() + " " + row.uri()
				+ " status=" + row.status() + " exception=" + row.exception() + ": incident " + row.count();
	}

	/** What the rules know of one service in the incident window. */
	private record Assessment(String service, List<String> slowFacts, boolean refusing, double share, double ratio,
			String answerFact, boolean restarted, long logLines) {

		boolean slow() {
			return !this.slowFacts.isEmpty();
		}

		boolean abnormal() {
			return slow() || this.refusing;
		}

		boolean healthy() {
			return !this.restarted && this.logLines == 0 && !abnormal();
		}

		List<String> abnormalFacts() {
			List<String> facts = new ArrayList<>();
			if (this.refusing) {
				facts.add(this.answerFact);
			}
			facts.addAll(this.slowFacts);
			return facts;
		}

		String healthFact() {
			return String.format(Locale.ROOT,
					"%s healthy: no restart and no WARN or ERROR line in the incident window, not slow (latency ratio"
							+ " %.2f), not refusing (%s)",
					this.service, this.ratio, this.answerFact.substring(this.service.length() + 2));
		}

	}

}
