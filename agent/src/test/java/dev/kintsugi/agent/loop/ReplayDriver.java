package dev.kintsugi.agent.loop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.io.ApplicationResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ReplayLabTools;
import dev.kintsugi.agent.lab.ToolResult;
import dev.kintsugi.agent.loop.ReplayCases.Recording;
import dev.kintsugi.agent.policy.Policy;
import dev.kintsugi.agent.policy.PolicyCounts;
import dev.kintsugi.agent.policy.PolicyTally;
import dev.kintsugi.agent.rules.RulesCandidate;
import dev.kintsugi.agent.rules.RulesDiagnosis;
import dev.kintsugi.agent.rules.RulesVersion;
import dev.kintsugi.agent.rules.RulesVerdict;

/**
 * The replay driver's per-sample pipeline (agent spec, "Replay of recorded tool outputs"; odd/tasks/replay-harness.md
 * T4, odd/tasks/decision-instrument.md T3): the real {@link AgentLoop} over {@link ReplayLabTools}, scored against the
 * recorded truth.
 *
 * <p>
 * A sample opens an incident with the recorded symptom and window of its primary recording, on a clock offset so that
 * "now" is that recording's start, and runs the loop to its end on the caller's thread. {@link ReplayCases} picks the
 * recordings from the manifest. An explorer or dossier sample of a fault is served the fault's {@code stress}
 * recordings, all modes pooled, its primary first: the first of them in the sample's mode, else the first of them.
 * When two recordings hold the same call with different outputs, the sample is served the primary's. A decision sample
 * is one case: a recording of the chosen split made in decision or dossier mode, which holds every call the dossier
 * makes, so it is served that recording alone. Each recording and the tool listing are parsed once; every sample gets a
 * fresh {@link ReplayLabTools}, so its counters are its own. A recording without {@code startedAt}, {@code symptom} or
 * a positive {@code sinceMinutes} fails before any model call: its fault's samples, or every case of its split. The
 * loop is built here, as {@code AgentLoopTest} builds it, rather than taken from a Spring context: one loop per sample,
 * each with its own tools and clock, needs no bean override and no production change. The model comes from the router
 * the caller passes: {@link ReplayRunTest} takes the application's own, so the route's real model and settings. A
 * decision sample writes its dossier in the run's view ({@code kintsugi.agent.decision.view}, the tables by default;
 * odd/tasks/service-assessment.md T1) and asks for the run's report ({@code kintsugi.agent.decision.report}, the plain
 * one by default; T2r), which its lines record, so a result is attributable to its variant.
 *
 * <p>
 * The {@code rules} route is Config 0 (ADR-016; odd/tasks/rules-baseline.md T3), decision mode only: a rules sample
 * gathers the same dossier over the same tools and clock, diagnoses it with {@link RulesDiagnosis} and is scored by the
 * same rule. It needs no router, no loop and no database ({@link #rulesOnly}), and production knows no such route: it
 * exists here only, as a baseline, never as the agent's answer.
 *
 * <p>
 * The gatherer, the rules and the loop read the topology of {@code kintsugi.agent.topology}, as the agent does, the
 * lab's by default ({@link #topology}; odd/tasks/rcaeval-converter.md T5): an RCAEval recording replays on its
 * system's file, since the dossier's calls follow the topology's services.
 *
 * <p>
 * The rules version is {@code kintsugi.agent.rules}, {@code v1} by default ({@link #rules}; resource-facts T5,
 * ADR-021): a rules sample diagnoses with it and records {@code rules-v1} or {@code rules-v2} as its model, and a
 * hybrid sample's report lists its candidates, its incident naming the version under {@code rules}.
 */
final class ReplayDriver {

	/** The committed recordings, relative to the agent module, where Maven runs the tests. */
	static final Path DEFAULT_RECORDINGS = Path.of("src/test/resources/replay");

	/** The recorded tool listing of mcp-tools' eight tools. */
	static final Path DEFAULT_TOOLS_LIST = Path.of("src/test/resources/mcp-outputs/tools-list.json");

	static final String SUMMARY_HEADER = String.join("\t", "fault", "mode", "route", "sample", "status", "truth",
			"reported", "verdict", "recorded", "unrecorded", "coverage", "conflicts", "promptTokens",
			"completionTokens", "seconds", "split", "recording", "view", "report", "model", "think", "reportCap",
			"reportTimeout", "numCtx", "candidates", "ranked", "riskyExecuted", "policyRefused", "level", "confidence");

	static final String AGGREGATE_HEADER = String.join("\t", "fault", "mode", "route", "split", "view", "report",
			"recordings", "samples", "matches", "meanCoverage", "coveredSamples", "matchesCovered", "dossierCovered",
			"promptTokens", "completionTokens", "costUsd", "model", "think", "reportCap", "reportTimeout", "numCtx");

	/**
	 * The coverage from which a sample counts as covered in {@code aggregate.tsv}: a dossier sample's model-chosen calls
	 * (free-text {@code search_runbooks} queries and the like) are often unrecorded, so a fully replayed dossier still
	 * shows about 0.91 (the T4 smoke check), while an explorer sample that lost most of its evidence falls well below.
	 */
	static final double COVERED_THRESHOLD = 0.8;

	/** The cloud route's price per million prompt and completion tokens, for the estimate only. */
	static final double PROMPT_USD_PER_MILLION = 0.10;

	static final double COMPLETION_USD_PER_MILLION = 0.50;

	/** Config 0's route in {@code summary.tsv}: replay-only, so no {@link AgentRoute} (ADR-016). */
	static final String RULES_ROUTE = "rules";

	/** Config 0's model in a rules sample's incident: the rules of ADR-016, version 1 ({@link RulesVersion#model}). */
	static final String RULES_MODEL = "rules-v1";

	private static final String RULES_ONLY = "a rules-only driver runs no model sample: use rulesSamples";

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final String NONE = "-";

	/** The truth of a healthy control (ADR-015), as the harness records it and the evals scorer reads it. */
	static final String HEALTHY_TRUTH = "NONE|NONE";

	/** The report that names no cause: the only right answer on a healthy control. */
	static final String UNKNOWN_ANSWER = Causes.UNKNOWN + "|" + Causes.UNKNOWN;

	private final ModelRouter router;

	private final LoopSettings settings;

	private final IncidentRepository incidents;

	private final JdbcClient admin;

	private final Path recordings;

	private final Path toolsList;

	private final ReplayCases cases;

	/** How a decision sample writes its dossier; explorer and dossier samples ignore it. */
	private final DossierView decisionView;

	/** Which report a decision sample asks for; explorer and dossier samples ignore it. */
	private final DecisionReport decisionReport;

	/** The system's topology, which the gatherer, the rules and the loop read. */
	private final Topology topology;

	/** The rules a rules sample diagnoses with and a hybrid sample's report takes its candidates from. */
	private final RulesVersion rules;

	/** Each recording, parsed and checked once. */
	private final Map<Recording, Parsed> parsed = new HashMap<>();

	private JsonNode toolsListNode;

	/** A driver whose decision samples write their dossier as the tables, the default view. */
	ReplayDriver(ModelRouter router, LoopSettings settings, IncidentRepository incidents, JdbcClient admin,
			Path recordings, Path toolsList) {
		this(router, settings, incidents, admin, recordings, toolsList, DossierView.TABLES);
	}

	/** A driver whose decision samples write their dossier in {@code decisionView} and ask for the plain report. */
	ReplayDriver(ModelRouter router, LoopSettings settings, IncidentRepository incidents, JdbcClient admin,
			Path recordings, Path toolsList, DossierView decisionView) {
		this(router, settings, incidents, admin, recordings, toolsList, decisionView, DecisionReport.PLAIN);
	}

	/**
	 * A driver whose decision samples write their dossier in {@code decisionView} and ask for {@code decisionReport}.
	 * The assessed report with any view but the sheets is refused only where decision mode applies them, before a
	 * decision run's first model call ({@link #decisionSamples}, {@link #decisionSample}); explorer and dossier samples
	 * ignore the pair, as the loop does.
	 * @param admin the agent database, to open the incident and read it back as the full view does
	 * @param recordings the replay directory: {@code recordings.tsv} and the files it names
	 * @param toolsList the recorded tool listing
	 * @param decisionView how a decision sample writes its dossier
	 * @param decisionReport which report a decision sample asks for
	 * @throws NullPointerException when {@code decisionView} or {@code decisionReport} is null, before any sample, as
	 * the loop refuses them
	 * @throws IllegalArgumentException when {@code kintsugi.agent.topology} names a missing or invalid file, or
	 * {@code kintsugi.agent.rules} a version other than {@code v1} or {@code v2}
	 */
	ReplayDriver(ModelRouter router, LoopSettings settings, IncidentRepository incidents, JdbcClient admin,
			Path recordings, Path toolsList, DossierView decisionView, DecisionReport decisionReport) {
		this(router, settings, incidents, admin, recordings, toolsList, decisionView, decisionReport,
				topology(System::getProperty), rules(System::getProperty));
	}

	/** A driver as above, over {@code topology} and with the rules version {@code rules}. */
	ReplayDriver(ModelRouter router, LoopSettings settings, IncidentRepository incidents, JdbcClient admin,
			Path recordings, Path toolsList, DossierView decisionView, DecisionReport decisionReport,
			Topology topology, RulesVersion rules) {
		this.decisionView = Objects.requireNonNull(decisionView, AgentLoop.NULL_VIEW);
		this.decisionReport = Objects.requireNonNull(decisionReport, AgentLoop.NULL_REPORT);
		this.router = router;
		this.settings = settings;
		this.incidents = incidents;
		this.admin = admin;
		this.recordings = recordings;
		this.toolsList = toolsList;
		this.topology = Objects.requireNonNull(topology);
		this.rules = Objects.requireNonNull(rules, "rules");
		this.cases = ReplayCases.read(recordings);
	}

	/**
	 * The topology a replay run reads: the file {@code property} names under {@link Topology#PROPERTY}, a Spring
	 * resource location as the agent takes it ({@code classpath:topology/re2-ob.json}, or a {@code file:} URL), else
	 * {@link Topology#DEFAULT_LOCATION}, the lab's. {@link Topology#load} reads and checks it, as at the agent's start.
	 * @param property a property's value, null when it is not set: the driver passes {@code System::getProperty}
	 * @throws IllegalArgumentException naming the file when it is missing, unreadable or invalid
	 */
	static Topology topology(Function<String, String> property) {
		String location = Objects.requireNonNullElse(property.apply(Topology.PROPERTY), Topology.DEFAULT_LOCATION);
		return Topology.load(ApplicationResourceLoader.get().getResource(location));
	}

	/**
	 * The rules version a replay run reads: the one {@code property} names under {@link RulesVersion#PROPERTY}, else
	 * {@link RulesVersion#DEFAULT}, Config 0.
	 * @param property a property's value, null when it is not set: the driver passes {@code System::getProperty}
	 * @throws IllegalArgumentException naming the property for any version but {@code v1} or {@code v2}
	 */
	static RulesVersion rules(Function<String, String> property) {
		return RulesVersion.of(Objects.requireNonNullElse(property.apply(RulesVersion.PROPERTY), RulesVersion.DEFAULT));
	}

	/**
	 * A driver for the {@code rules} route only: no router, no loop settings and no database, since a rules sample
	 * calls no model and opens no incident. Its model samples refuse to run ({@link #sample}, {@link #decisionSample}).
	 * It reads the topology of {@code kintsugi.agent.topology} ({@link #topology}) and the rules version of
	 * {@code kintsugi.agent.rules} ({@link #rules}).
	 */
	static ReplayDriver rulesOnly(Path recordings, Path toolsList, DossierView decisionView,
			DecisionReport decisionReport) {
		return rulesOnly(recordings, toolsList, decisionView, decisionReport, topology(System::getProperty));
	}

	/** A driver for the {@code rules} route only, as {@link #rulesOnly} above, over {@code topology}. */
	static ReplayDriver rulesOnly(Path recordings, Path toolsList, DossierView decisionView,
			DecisionReport decisionReport, Topology topology) {
		return rulesOnly(recordings, toolsList, decisionView, decisionReport, topology, rules(System::getProperty));
	}

	/** A driver for the {@code rules} route only, over {@code topology}, diagnosing with {@code rules}. */
	static ReplayDriver rulesOnly(Path recordings, Path toolsList, DossierView decisionView,
			DecisionReport decisionReport, Topology topology, RulesVersion rules) {
		return new ReplayDriver(null, null, null, null, recordings, toolsList, decisionView, decisionReport, topology,
				rules);
	}

	/**
	 * Refuses, before any model call, a property of the other kind of run, so a run is never other than the one asked
	 * for: decision mode replays the cases of {@code replay.split}, each {@code replay.repeats} times, and takes no
	 * {@code replay.samples}; explorer and dossier mode take {@code replay.samples} per fault, and neither of the other
	 * two, nor the dossier view ({@link DossierView#PROPERTY}) or the report ({@link DecisionReport#PROPERTY}), which
	 * only decision mode reads.
	 * @param property a property's value, null when it is not set: {@link ReplayRunTest} passes
	 * {@code System::getProperty}
	 * @throws IllegalArgumentException naming the first such property and why it does not apply
	 */
	static void refuseIgnoredProperties(boolean decision, Function<String, String> property) {
		if (decision) {
			refuseIfSet(property, "replay.samples",
					"decision mode replays each recording of replay.split once; to rerun each one, set replay.repeats");
		}
		else {
			refuseIfSet(property, "replay.split",
					"only decision mode replays a split; explorer and dossier pool the fault's stress recordings");
			refuseIfSet(property, "replay.repeats",
					"only decision mode takes it; explorer and dossier take replay.samples");
			refuseIfSet(property, DossierView.PROPERTY,
					"only decision mode reads it; explorer has no dossier, and dossier mode writes the tables");
			refuseIfSet(property, DecisionReport.PROPERTY,
					"only decision mode reads it; explorer and dossier mode always ask for the plain report");
			refuseIfSet(property, RulesVersion.PROPERTY,
					"only decision mode reads it, on the rules route and in the hybrid report");
		}
	}

	private static void refuseIfSet(Function<String, String> property, String name, String why) {
		if (property.apply(name) != null) {
			throw new IllegalArgumentException(name + " does not apply to this mode: " + why);
		}
	}

	/**
	 * Runs explorer or dossier sample {@code n} of {@code fault} to its end, over the fault's {@code stress} recordings
	 * ({@link ReplayCases#pooled}). Such a sample is no case of a split: its split is {@code -}.
	 * @throws IllegalArgumentException when the fault has no {@code stress} recording, and in decision mode, which
	 * replays one recording per case ({@link #decisionCases})
	 * @throws IllegalStateException when the loop did not run the incident
	 */
	Sample sample(String fault, AgentMode mode, int n) {
		if (mode == AgentMode.DECISION) {
			throw new IllegalArgumentException("a decision sample replays one recording, a case of a split, never "
					+ "every recording of " + fault + ": see decisionCases and decisionSample");
		}
		List<Parsed> pooled = this.cases.pooled(fault).stream().map(this::parsed).toList();
		Parsed primary = first(pooled, mode).orElse(pooled.get(0));
		// The primary recording first: ReplayLabTools keeps the first output of a call two recordings hold.
		List<JsonNode> served = new ArrayList<>();
		served.add(primary.incident());
		pooled.stream().filter(recording -> recording != primary).forEach(recording -> served.add(recording.incident()));
		return run(primary, served, mode, NONE, n);
	}

	/**
	 * The decision cases of {@code split} for {@code faults}, as {@link ReplayCases#decisionCases} picks them.
	 * @throws IllegalArgumentException for an unknown split, or a chosen fault with no case in it
	 */
	List<Recording> decisionCases(String split, List<String> faults) {
		return this.cases.decisionCases(split, faults);
	}

	/**
	 * Runs every decision case of {@code split} for {@code faults} {@code repeats} times, case by case, and hands each
	 * sample to {@code each} as it ends. Every case's recording is parsed and checked before the first sample, so a
	 * broken one fails the run before any model call.
	 * @throws IllegalArgumentException as {@link #decisionCases} does, or for a recording that lacks its start, symptom
	 * or window
	 */
	void decisionSamples(String split, List<String> faults, int repeats, Consumer<Sample> each) {
		eachCase(split, faults, repeats, each, this::decisionSample);
	}

	/**
	 * Runs every decision case of {@code split} for {@code faults} {@code repeats} times on the {@code rules} route, as
	 * {@link #decisionSamples} runs them on a model's.
	 * @throws IllegalArgumentException as {@link #decisionSamples} does
	 */
	void rulesSamples(String split, List<String> faults, int repeats, Consumer<Sample> each) {
		eachCase(split, faults, repeats, each, this::rulesSample);
	}

	private void eachCase(String split, List<String> faults, int repeats, Consumer<Sample> each,
			BiFunction<Recording, Integer, Sample> sample) {
		checkReport(AgentMode.DECISION);
		List<Recording> cases = decisionCases(split, faults);
		cases.forEach(this::parsed);
		for (Recording recording : cases) {
			for (int n = 1; n <= repeats; n++) {
				each.accept(sample.apply(recording, n));
			}
		}
	}

	/**
	 * Runs repeat {@code n} of decision case {@code recording} on the {@code rules} route: the dossier a decision sample's
	 * loop gathers, over the same {@link ReplayLabTools} and clock and with the recording's window, diagnosed by
	 * the run's rules version ({@link RulesDiagnosis} for {@code v1}). It always completes, with 0 tokens, and its counters are the gatherer's calls, which are
	 * the dossier's. Its incident holds the verdict, its rule and its facts; no database row is written.
	 * @throws IllegalArgumentException when the recording lacks its start, symptom or window
	 */
	Sample rulesSample(Recording recording, int n) {
		checkReport(AgentMode.DECISION);
		Parsed parsed = parsed(recording);
		ReplayLabTools tools = new ReplayLabTools(toolsList(), List.of(parsed.incident()));
		CoverageCounter counter = new CoverageCounter(tools);
		Clock clock = clockAt(parsed.startedAt());
		long start = System.nanoTime();
		PolicyTally gathering = new PolicyTally();
		Dossier dossier = new DossierGatherer(counter, clock, this.topology, Policy.DEFAULT).gather(parsed.sinceMinutes(),
				gathering);
		RulesVerdict verdict = this.rules.diagnose(dossier, this.topology);
		double seconds = (System.nanoTime() - start) / 1e9;
		// The candidates a hybrid run would list on the same dossier (hybrid T4); the verdict above is unchanged.
		List<RulesCandidate> candidates = this.rules.candidates(dossier, this.topology);

		AgentMode mode = AgentMode.DECISION;
		String reported = verdict.service() + "|" + verdict.category();
		ObjectNode incident = JSON.createObjectNode();
		incident.put("id", UUID.randomUUID().toString());
		incident.put("symptom", parsed.symptom());
		incident.put("sinceMinutes", parsed.sinceMinutes());
		incident.put("mode", mode.value());
		incident.put("route", RULES_ROUTE);
		incident.put("model", this.rules.model());
		incident.put("status", IncidentStatus.COMPLETED.name());
		incident.put("startedAt", clock.instant().toString());
		incident.putObject("report")
			.put("rootCauseService", verdict.service())
			.put("rootCauseCategory", verdict.category());
		incident.put("rule", verdict.rule());
		ArrayNode facts = incident.putArray("facts");
		verdict.facts().forEach(facts::add);
		incident.put("view", this.decisionView.value());
		incident.put("decisionReport", this.decisionReport.value());
		// The gatherer's calls are the sample's only calls: its counts are what the gatherer's tally recorded (ADR-027).
		policy(incident, gathering.counts(Policy.DEFAULT));
		incident.putArray("steps");

		String truth = recording.truth();
		return new Sample(recording.fault(), mode, RULES_ROUTE, recording.split(), recording.stem(),
				mode.viewFrom(this.decisionView), mode.reportFrom(this.decisionReport), n, IncidentStatus.COMPLETED,
				truth, reported, verdict(IncidentStatus.COMPLETED, reported, truth), tools.recordedCalls(),
				tools.unrecordedCalls(), counter.coverage(tools.recordedCalls(), tools.unrecordedCalls()),
				tools.conflicts(), 0, 0, seconds,
				tools.recordedCalls() + tools.unrecordedCalls(), tools.unrecordedCalls(), incident,
				Settings.rules(this.rules),
				candidates, counter.resourceUnrecorded);
	}

	/** A clock whose "now" is {@code startedAt}, then running: the recording's start, as the sample sees it. */
	private static Clock clockAt(Instant startedAt) {
		return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), startedAt));
	}

	/**
	 * Runs repeat {@code n} of decision case {@code recording} to its end, over that recording alone: it holds every
	 * call the dossier makes, so pooling it with others would only add other incidents' outputs.
	 * @throws IllegalArgumentException when the recording lacks its start, symptom or window
	 * @throws IllegalStateException when the loop did not run the incident
	 */
	Sample decisionSample(Recording recording, int n) {
		checkReport(AgentMode.DECISION);
		Parsed parsed = parsed(recording);
		return run(parsed, List.of(parsed.incident()), AgentMode.DECISION, recording.split(), n);
	}

	/**
	 * Runs one sample in {@code mode} to its end over the {@code served} recordings, opened on {@code primary}'s
	 * symptom and window with "now" at its start, and scores it against its truth. A sample records the view and the
	 * report its mode applies ({@link AgentMode#viewFrom}, {@link AgentMode#reportFrom}): a decision sample the run's,
	 * the others none, written {@code -}.
	 */
	/**
	 * Checks, before any model call, that {@code mode} can ask for its report in its view, by the loop's own rule: only
	 * a mode that applies the report ({@link AgentMode#reportFrom}), decision mode, checks it, so an explorer or dossier
	 * run ignores the pair as the loop does.
	 * @throws IllegalArgumentException for the assessed report with any view but the sheets, in decision mode
	 */
	private void checkReport(AgentMode mode) {
		mode.reportFrom(this.decisionReport)
			.ifPresent(report -> report.checkView(mode.viewFrom(this.decisionView).orElse(DossierView.TABLES)));
	}

	private Sample run(Parsed primary, List<JsonNode> served, AgentMode mode, String split, int n) {
		if (this.router == null) {
			throw new IllegalStateException(RULES_ONLY);
		}
		String fault = primary.recording().fault();
		ReplayLabTools tools = new ReplayLabTools(toolsList(), served);
		CoverageCounter counter = new CoverageCounter(tools);
		Clock clock = clockAt(primary.startedAt());

		UUID id = open(primary.symptom(), primary.sinceMinutes(), clock.instant());
		AgentLoop.Outcome outcome;
		long start = System.nanoTime();
		try (AgentLoop loop = new AgentLoop(this.incidents, this.router, counter, this.settings, clock, this.topology, mode,
				this.decisionView, this.decisionReport, this.rules)) {
			outcome = loop.runToOutcome(id);
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		if (outcome == null) {
			throw new IllegalStateException("the loop did not run incident " + id + " of " + fault);
		}
		IncidentStatus status = outcome.status();

		ObjectNode incident = incident(id);
		policy(incident, outcome.policy());
		// A hybrid sample's candidates came from the run's rules version, which its model column does not name.
		if (mode.reportFrom(this.decisionReport).orElse(null) == DecisionReport.HYBRID) {
			incident.put("rules", this.rules.value());
		}
		JsonNode report = incident.path("report");
		String reported = report.isObject()
				? report.path("rootCauseService").asString("") + "|" + report.path("rootCauseCategory").asString("")
				: "none";
		ArrayNode dossier = dossierCalls(incident);
		int dossierUnrecorded = 0;
		int dossierResourceUnrecorded = 0;
		for (JsonNode call : dossier) {
			if (call.path("output").asString("").startsWith(ReplayLabTools.NOT_RECORDED)) {
				dossierUnrecorded++;
				if (CoverageCounter.resource(call.path("tool").asString(""), call.path("args"))) {
					dossierResourceUnrecorded++;
				}
			}
		}
		String route = this.router.route(ModelTask.ACT).route().value();
		String truth = primary.recording().truth();
		return new Sample(fault, mode, route, split, primary.recording().stem(), mode.viewFrom(this.decisionView),
				mode.reportFrom(this.decisionReport), n,
				status, truth, reported, verdict(status, reported, truth), tools.recordedCalls(),
				tools.unrecordedCalls(), counter.coverage(tools.recordedCalls(), tools.unrecordedCalls()),
				tools.conflicts(), total(incident, "promptTokens"),
				total(incident, "completionTokens"), seconds, dossier.size(), dossierUnrecorded, incident,
				Settings.of(this.router.route(ModelTask.REPORT)), outcome.candidates(), dossierResourceUnrecorded);
	}

	/**
	 * Records in the sample's incident what the policy did (ADR-027): {@code riskyExecuted} and {@code policyRefused},
	 * the two columns of {@code summary.tsv} before the level.
	 */
	private static void policy(ObjectNode incident, PolicyCounts counts) {
		incident.put("riskyExecuted", counts.riskyExecuted());
		incident.put("policyRefused", counts.refused());
	}

	/** The fault's first recording made in {@code mode}, if any. */
	private static Optional<Parsed> first(List<Parsed> ofFault, AgentMode mode) {
		return ofFault.stream().filter(recording -> recording.recording().mode().equals(mode.value())).findFirst();
	}

	/**
	 * The rule of {@code agent_verdict} in {@code lib.sh}: a completed report naming the truth, else a miss. On a
	 * healthy control (truth {@link #HEALTHY_TRUTH}, ADR-015) only a completed {@link #UNKNOWN_ANSWER} matches; a named
	 * cause is a false alarm, a miss here, which the evals scorer counts as {@code false_alarm}.
	 */
	static String verdict(IncidentStatus status, String reported, String truth) {
		boolean completed = status == IncidentStatus.COMPLETED;
		if (HEALTHY_TRUTH.equals(truth)) {
			return completed && UNKNOWN_ANSWER.equals(reported) ? "MATCH" : "MISS";
		}
		return completed && truth != null && !truth.isEmpty() && truth.equals(reported) ? "MATCH" : "MISS";
	}

	/**
	 * Writes {@code <fault>-<mode>-<recording>-<n>.json} and appends the sample's line to {@code summary.tsv}, header
	 * first.
	 */
	static void write(Path out, Sample sample) {
		try {
			Files.createDirectories(out);
			String name = String.join("-", sample.fault(), sample.mode().value(), sample.recording(),
					String.valueOf(sample.n()));
			Files.writeString(out.resolve(name + ".json"),
					JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sample.incident()));
			Path summary = out.resolve("summary.tsv");
			if (!Files.exists(summary)) {
				Files.writeString(summary, SUMMARY_HEADER + "\n");
			}
			Files.writeString(summary, sample.summaryLine() + "\n", StandardOpenOption.APPEND);
		}
		catch (IOException failed) {
			throw new UncheckedIOException(failed);
		}
	}

	/**
	 * One line per fault, mode, route, split, view, report and route settings ({@link Settings}), in the samples' order,
	 * the settings written last, after the columns of every run before them: the distinct recordings the samples
	 * opened on, the samples, matches out of samples, mean coverage, the covered samples (coverage at least
	 * {@link #COVERED_THRESHOLD}) and the matches among them, the samples whose {@code DOSSIER} step made calls and had
	 * none unrecorded but resource calls out of samples, tokens and, on the cloud route, the estimated cost at
	 * {@link #PROMPT_USD_PER_MILLION} and {@link #COMPLETION_USD_PER_MILLION}; on the {@code rules} route, 0. With repeats, the samples outnumber the
	 * recordings, which are the independent observations.
	 */
	static List<String> aggregate(List<Sample> samples) {
		Map<List<String>, List<Sample>> groups = new LinkedHashMap<>();
		for (Sample sample : samples) {
			groups.computeIfAbsent(List.of(String.join("\t", sample.fault(), sample.mode().value(), sample.route(),
					sample.split(), sample.viewColumn(), sample.reportColumn()), sample.settings().columns()),
					key -> new ArrayList<>())
				.add(sample);
		}
		List<String> lines = new ArrayList<>();
		lines.add(AGGREGATE_HEADER);
		groups.forEach((key, group) -> {
			long recordings = group.stream().map(Sample::recording).distinct().count();
			long matches = group.stream().filter(sample -> sample.verdict().equals("MATCH")).count();
			double coverage = group.stream().mapToDouble(Sample::coverage).average().orElse(0);
			List<Sample> covered = group.stream().filter(sample -> sample.coverage() >= COVERED_THRESHOLD).toList();
			long matchesCovered = covered.stream().filter(sample -> sample.verdict().equals("MATCH")).count();
			long dossierCovered = group.stream()
				.filter(sample -> sample.dossierCalls() > 0
						&& sample.dossierUnrecorded() == sample.dossierResourceUnrecorded())
				.count();
			Long prompt = sum(group.stream().map(Sample::promptTokens).toList());
			Long completion = sum(group.stream().map(Sample::completionTokens).toList());
			String route = group.get(0).route();
			boolean cloud = route.equals(AgentRoute.CLOUD.value());
			// Config 0 calls no model, so it costs nothing, and says so; the local route's cost is not estimated.
			String cost = route.equals(RULES_ROUTE) ? format("%.4f", 0)
					: !cloud || (prompt == null && completion == null) ? NONE
							: format("%.4f", (orZero(prompt) * PROMPT_USD_PER_MILLION
									+ orZero(completion) * COMPLETION_USD_PER_MILLION) / 1_000_000);
			lines.add(String.join("\t", key.get(0), String.valueOf(recordings), String.valueOf(group.size()),
					matches + "/" + group.size(), format("%.2f", coverage), String.valueOf(covered.size()),
					matchesCovered + "/" + covered.size(), dossierCovered + "/" + group.size(), shown(prompt),
					shown(completion), cost, key.get(1)));
		});
		return lines;
	}

	/** The recording, parsed and checked on first use. */
	private Parsed parsed(Recording recording) {
		return this.parsed.computeIfAbsent(recording, this::parse);
	}

	/**
	 * Reads the recording's incident and the start, symptom and window a sample opens with.
	 * @throws IllegalArgumentException when it lacks one of them
	 */
	private Parsed parse(Recording recording) {
		JsonNode incident = JSON.readTree(this.recordings.resolve(recording.file()).toFile());
		String startedAt = incident.path("startedAt").asString("");
		String symptom = incident.path("symptom").asString("");
		JsonNode since = incident.path("sinceMinutes");
		String problem = startedAt.isBlank() ? "no startedAt"
				: symptom.isBlank() ? "no symptom"
						: !since.isIntegralNumber() || since.asInt() <= 0 ? "no positive sinceMinutes" : null;
		Instant start = null;
		if (problem == null) {
			try {
				start = Instant.parse(startedAt);
			}
			catch (DateTimeParseException unreadable) {
				problem = "an unreadable startedAt '" + startedAt + "'";
			}
		}
		if (problem != null) {
			throw new IllegalArgumentException(
					"the recording " + recording.file() + " of " + recording.fault() + " has " + problem);
		}
		return new Parsed(recording, incident, start, symptom, since.asInt());
	}

	private JsonNode toolsList() {
		if (this.toolsListNode == null) {
			this.toolsListNode = JSON.readTree(this.toolsList.toFile());
		}
		return this.toolsListNode;
	}

	private UUID open(String symptom, int sinceMinutes, Instant startedAt) {
		UUID id = UUID.randomUUID();
		ObjectNode signal = JSON.createObjectNode().put("symptom", symptom).put("sinceMinutes", sinceMinutes);
		this.admin
			.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, CAST(? AS jsonb), 'RUNNING', ?)")
			.params(id, JSON.writeValueAsString(signal), Timestamp.from(startedAt))
			.update();
		return id;
	}

	/** The incident and its steps, as {@code GET /incidents/{id}?full=true} shows them, nulls left out. */
	private ObjectNode incident(UUID id) {
		ObjectNode incident = this.admin.sql("""
				SELECT id, signal->>'symptom' AS symptom, (signal->>'sinceMinutes')::int AS since_minutes, mode, route,
				       model, status, failure_reason, started_at, ended_at, report::text AS report
				FROM incidents WHERE id = ?""").param(id).query((rs, row) -> {
			ObjectNode node = JSON.createObjectNode();
			node.put("id", rs.getObject("id", UUID.class).toString());
			text(node, "symptom", rs.getString("symptom"));
			number(node, "sinceMinutes", rs.getObject("since_minutes", Integer.class));
			text(node, "mode", rs.getString("mode"));
			text(node, "route", rs.getString("route"));
			text(node, "model", rs.getString("model"));
			text(node, "status", rs.getString("status"));
			text(node, "failureReason", rs.getString("failure_reason"));
			instant(node, "startedAt", rs, "started_at");
			instant(node, "endedAt", rs, "ended_at");
			tree(node, "report", rs.getString("report"));
			return node;
		}).single();
		ArrayNode steps = incident.putArray("steps");
		this.admin.sql("""
				SELECT step_no, kind, phase, model, tokens, prompt_tokens, completion_tokens, tool_name,
				       tool_args::text AS tool_args, tool_outcome, summary, tool_output, model_output, latency_ms,
				       created_at
				FROM incident_steps WHERE incident_id = ? ORDER BY step_no""").param(id).query((rs, row) -> {
			ObjectNode step = JSON.createObjectNode();
			step.put("stepNo", rs.getInt("step_no"));
			text(step, "kind", rs.getString("kind"));
			text(step, "phase", rs.getString("phase"));
			text(step, "model", rs.getString("model"));
			number(step, "tokens", rs.getObject("tokens", Integer.class));
			number(step, "promptTokens", rs.getObject("prompt_tokens", Integer.class));
			number(step, "completionTokens", rs.getObject("completion_tokens", Integer.class));
			text(step, "toolName", rs.getString("tool_name"));
			tree(step, "toolArgs", rs.getString("tool_args"));
			text(step, "toolOutcome", rs.getString("tool_outcome"));
			text(step, "summary", rs.getString("summary"));
			text(step, "toolOutput", rs.getString("tool_output"));
			text(step, "modelOutput", rs.getString("model_output"));
			number(step, "latencyMs", rs.getObject("latency_ms", Integer.class));
			instant(step, "createdAt", rs, "created_at");
			return step;
		}).list().forEach(steps::add);
		return incident;
	}

	/** The {@code DOSSIER} step's calls, empty when the run has none. */
	private static ArrayNode dossierCalls(ObjectNode incident) {
		for (JsonNode step : incident.path("steps")) {
			if (step.path("kind").asString("").equals("DOSSIER") && step.path("toolOutput").isString()) {
				JsonNode calls = JSON.readTree(step.path("toolOutput").asString());
				if (calls instanceof ArrayNode array) {
					return array;
				}
			}
		}
		return JSON.createArrayNode();
	}

	/** A token count over the steps, null when no step counted it (as {@code incident_fields} totals none, not 0). */
	private static Integer total(ObjectNode incident, String field) {
		Integer total = null;
		for (JsonNode step : incident.path("steps")) {
			if (step.path(field).isInt()) {
				total = (total == null ? 0 : total) + step.path(field).asInt();
			}
		}
		return total;
	}

	private static Long sum(List<Integer> counts) {
		Long total = null;
		for (Integer count : counts) {
			if (count != null) {
				total = (total == null ? 0L : total) + count;
			}
		}
		return total;
	}

	private static long orZero(Long count) {
		return count == null ? 0 : count;
	}

	private static String shown(Number count) {
		return count == null ? NONE : count.toString();
	}

	private static String format(String pattern, double value) {
		return String.format(Locale.ROOT, pattern, value);
	}

	private static void text(ObjectNode node, String field, String value) {
		if (value != null) {
			node.put(field, value);
		}
	}

	private static void number(ObjectNode node, String field, Integer value) {
		if (value != null) {
			node.put(field, value);
		}
	}

	private static void tree(ObjectNode node, String field, String json) {
		if (json != null) {
			node.set(field, JSON.readTree(json));
		}
	}

	private static void instant(ObjectNode node, String field, ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		if (value != null) {
			node.put(field, value.toInstant().toString());
		}
	}

	/** A recording's row, its parsed incident and the start, symptom and window a sample opens with. */
	private record Parsed(Recording recording, JsonNode incident, Instant startedAt, String symptom,
			int sinceMinutes) {
	}

	/**
	 * One scored sample. {@code split} is its case's split, or {@code -} for an explorer or dossier sample, which is no
	 * case; {@code recording} is the stem of the recording it opened on; {@code view} is the dossier view of a decision
	 * sample, none for the others, which apply none; {@code report} is likewise the report a decision sample asked for.
	 * The four come in the order of their {@code summary.tsv} columns. {@code settings} are the route settings the sample
	 * ran with ({@link Settings}), and {@code candidates} the rules' candidates of a rules sample or the list a hybrid
	 * run's report task gave the model, empty for every other sample (hybrid T4), its last column.
	 * {@code recorded}, {@code unrecorded} and {@code conflicts} are the sample's {@link ReplayLabTools} counters, and
	 * {@code coverage} their share of recorded calls without the resource calls ({@link CoverageCounter}); {@code dossierCalls} and {@code dossierUnrecorded} count the {@code DOSSIER}
	 * step's calls and those no recording held, and {@code dossierResourceUnrecorded} those among them that are resource
	 * calls, which a fully recorded dossier may leave unrecorded (resource-facts T4); a token count is null when no
	 * step counted it.
	 */
	record Sample(String fault, AgentMode mode, String route, String split, String recording,
			Optional<DossierView> view, Optional<DecisionReport> report, int n, IncidentStatus status, String truth,
			String reported, String verdict, int recorded, int unrecorded, double coverage, int conflicts,
			Integer promptTokens, Integer completionTokens, double seconds, int dossierCalls, int dossierUnrecorded,
			ObjectNode incident, Settings settings, List<RulesCandidate> candidates, int dossierResourceUnrecorded) {

		/** A sample whose dossier left no resource call unrecorded. */
		Sample(String fault, AgentMode mode, String route, String split, String recording, Optional<DossierView> view,
				Optional<DecisionReport> report, int n, IncidentStatus status, String truth, String reported,
				String verdict, int recorded, int unrecorded, double coverage, int conflicts, Integer promptTokens,
				Integer completionTokens, double seconds, int dossierCalls, int dossierUnrecorded, ObjectNode incident,
				Settings settings, List<RulesCandidate> candidates) {
			this(fault, mode, route, split, recording, view, report, n, status, truth, reported, verdict, recorded,
					unrecorded, coverage, conflicts, promptTokens, completionTokens, seconds, dossierCalls,
					dossierUnrecorded, incident, settings, candidates, 0);
		}

		/** This sample with {@code count} of its dossier's unrecorded calls being resource calls. */
		Sample withResourceUnrecorded(int count) {
			return new Sample(this.fault, this.mode, this.route, this.split, this.recording, this.view, this.report,
					this.n, this.status, this.truth, this.reported, this.verdict, this.recorded, this.unrecorded,
					this.coverage, this.conflicts, this.promptTokens, this.completionTokens, this.seconds,
					this.dossierCalls, this.dossierUnrecorded, this.incident, this.settings, this.candidates, count);
		}

		/** The sample's line of {@code summary.tsv}, in {@link #SUMMARY_HEADER}'s columns. */
		String summaryLine() {
			return String.join("\t", this.fault, this.mode.value(), this.route, String.valueOf(this.n),
					this.status.name(), this.truth, this.reported, this.verdict, String.valueOf(this.recorded),
					String.valueOf(this.unrecorded), format("%.2f", this.coverage), String.valueOf(this.conflicts),
					shown(this.promptTokens), shown(this.completionTokens), format("%.1f", this.seconds), this.split,
					this.recording, viewColumn(), reportColumn(), this.settings.columns(), candidatesColumn(),
					rankedColumn(), policyColumn("riskyExecuted"), policyColumn("policyRefused"), levelColumn(),
					confidenceColumn());
		}

		/**
		 * The verification level as {@code summary.tsv} writes it, before the confidence (verification-levels T5; ADR-028): the
		 * stored report's {@code verification.level}, {@code L0} to {@code L3}, or {@code -} for a run that stored no
		 * report and for a rules line, since Config 0 writes no cited evidence to check.
		 */
		String levelColumn() {
			if (RULES_ROUTE.equals(this.route)) {
				return NONE;
			}
			JsonNode level = this.incident.path("report").path("verification").path("level");
			return level.isString() ? level.asString() : NONE;
		}

		/**
		 * The top-1 confidence as {@code summary.tsv} writes it, its last column (config-c T2; ADR-030): the stored
		 * report's {@code confidence}, 0 to 1, so Config C's escalation rule can read it; empty for a run that stored no
		 * report. A forced abstention stores 0.0.
		 */
		String confidenceColumn() {
			JsonNode confidence = this.incident.path("report").path("confidence");
			return confidence.isNumber() ? String.valueOf(confidence.asDouble()) : "";
		}

		/**
		 * One of the policy's counts as {@code summary.tsv} writes them (ADR-027): the number its incident records, or
		 * {@code -} for a hand-made sample whose incident records none.
		 */
		String policyColumn(String field) {
			JsonNode count = this.incident.path(field);
			return count.isInt() ? String.valueOf(count.asInt()) : NONE;
		}

		/** The candidates as {@code summary.tsv} writes them: each {@code service|category}, in order, joined by ';'. */
		String candidatesColumn() {
			return this.candidates.stream()
				.map(candidate -> candidate.service() + "|" + candidate.category())
				.collect(Collectors.joining(";"));
		}

		/**
		 * The model's ranked alternatives as {@code summary.tsv} writes them (ranked-report T2; ADR-025): each
		 * {@code service|category} of the stored report's {@code alternatives}, in order, joined by ';'. Empty when the
		 * sample has none: a rules sample, a hybrid or assessed one, or a run that stored no report.
		 */
		String rankedColumn() {
			JsonNode alternatives = this.incident.path("report").path("alternatives");
			if (!alternatives.isArray()) {
				return "";
			}
			return alternatives.valueStream()
				.map(item -> item.path("rootCauseService").asString("") + "|"
						+ item.path("rootCauseCategory").asString(""))
				.collect(Collectors.joining(";"));
		}

		/** The view as {@code summary.tsv} and {@code aggregate.tsv} write it: its value, or {@code -} for none. */
		String viewColumn() {
			return this.view.map(DossierView::value).orElse(NONE);
		}

		/** The report as {@code summary.tsv} and {@code aggregate.tsv} write it: its value, or {@code -} for none. */
		String reportColumn() {
			return this.report.map(DecisionReport::value).orElse(NONE);
		}

	}

	/**
	 * The route settings a sample ran with, the last five columns of {@code aggregate.tsv} and the five before
	 * {@code summary.tsv}'s candidates (agent spec, "Replay of recorded tool outputs"; odd/tasks/qwen-dev.md T3): the model, its thinking setting
	 * ({@code on}, {@code off} or {@code default}, see {@link ModelRoute#thinking}), the report's answer cap, the
	 * report's deadline, written in seconds with an {@code s}, and the context window, the local route's
	 * {@code num-ctx} or {@code -} on a route with no known window (context-full-cut T3). They are the route's
	 * effective values, overridden or not, so a line is attributable to the model and the settings that wrote it. The
	 * rules call no model: their model is their version's ({@link RulesVersion#model}: {@link #RULES_MODEL} for Config
	 * 0, {@code rules-v2} for Config 0R) and the other four are {@code -}.
	 */
	record Settings(String model, String think, String reportCap, String reportTimeout, String numCtx) {

		static final Settings RULES = rules(RulesVersion.V1);

		/** The settings of a rules sample diagnosed by {@code rules}. */
		static Settings rules(RulesVersion rules) {
			return new Settings(rules.model(), NONE, NONE, NONE, NONE);
		}

		/** The settings of {@code route}'s report call. */
		static Settings of(ModelRoute route) {
			Duration deadline = route.callTimeouts().report();
			String timeout = deadline.toMillis() % 1000 == 0 ? deadline.toSeconds() + "s" : deadline.toMillis() + "ms";
			OptionalInt window = route.contextWindow();
			return new Settings(route.model(), route.thinking(), String.valueOf(route.numPredict().report()), timeout,
					window.isPresent() ? String.valueOf(window.getAsInt()) : NONE);
		}

		/** The five columns, tab-separated. */
		String columns() {
			return String.join("\t", this.model, this.think, this.reportCap, this.reportTimeout, this.numCtx);
		}

	}

	/**
	 * The calls a sample makes through its {@link ReplayLabTools}, passed on unchanged, with the resource calls
	 * counted apart (resource-facts T4, the human's choice of 2026-11-01): the lab recordings predate the dossier's
	 * {@code query_metric} calls of {@link DossierGatherer#RESOURCE_METRICS}, so the coverage leaves them out of both
	 * its terms, while {@code unrecorded} still counts every unrecorded call, so the gap stays visible.
	 */
	static final class CoverageCounter implements LabTools {

		private final LabTools tools;

		private int resourceCalls;

		/** The resource calls no recording held. */
		int resourceUnrecorded;

		CoverageCounter(LabTools tools) {
			this.tools = tools;
		}

		@Override
		public ToolResult call(String tool, String jsonArgs) {
			ToolResult result = this.tools.call(tool, jsonArgs);
			if (resource(tool, jsonArgs)) {
				this.resourceCalls++;
				if (result.rawOutput().startsWith(ReplayLabTools.NOT_RECORDED)) {
					this.resourceUnrecorded++;
				}
			}
			return result;
		}

		@Override
		public List<ToolCallback> toolsFor(Phase phase) {
			return this.tools.toolsFor(phase);
		}

		/** The coverage of {@code recorded} and {@code unrecorded} calls, the resource calls left out. */
		double coverage(int recorded, int unrecorded) {
			return coverage(recorded, unrecorded, this.resourceCalls, this.resourceUnrecorded);
		}

		/** The share of the calls but the resource calls answered from a recording; 1 when there is none. */
		static double coverage(int recorded, int unrecorded, int resourceCalls, int resourceUnrecorded) {
			int calls = recorded + unrecorded - resourceCalls;
			return calls <= 0 ? 1.0 : (double) (recorded - (resourceCalls - resourceUnrecorded)) / calls;
		}

		private static boolean resource(String tool, String jsonArgs) {
			if (!"query_metric".equals(tool)) {
				return false;
			}
			try {
				return resource(tool, JSON.readTree(jsonArgs));
			}
			catch (RuntimeException unreadable) {
				return false;
			}
		}

		/** Whether a call is a {@code query_metric} call of a resource metric. */
		static boolean resource(String tool, JsonNode args) {
			return "query_metric".equals(tool)
					&& DossierGatherer.RESOURCE_METRICS.contains(args.path("metric").asString(""));
		}

	}

}
