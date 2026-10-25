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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.ReplayLabTools;
import dev.kintsugi.agent.loop.ReplayCases.Recording;

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
 * odd/tasks/service-assessment.md T1), which its lines record, so a result is attributable to its variant.
 */
final class ReplayDriver {

	/** The committed recordings, relative to the agent module, where Maven runs the tests. */
	static final Path DEFAULT_RECORDINGS = Path.of("src/test/resources/replay");

	/** The recorded tool listing of mcp-tools' eight tools. */
	static final Path DEFAULT_TOOLS_LIST = Path.of("src/test/resources/mcp-outputs/tools-list.json");

	static final String SUMMARY_HEADER = String.join("\t", "fault", "mode", "route", "sample", "status", "truth",
			"reported", "verdict", "recorded", "unrecorded", "coverage", "conflicts", "promptTokens",
			"completionTokens", "seconds", "split", "recording", "view");

	static final String AGGREGATE_HEADER = String.join("\t", "fault", "mode", "route", "split", "view", "recordings",
			"samples", "matches", "meanCoverage", "coveredSamples", "matchesCovered", "dossierCovered", "promptTokens",
			"completionTokens", "costUsd");

	/**
	 * The coverage from which a sample counts as covered in {@code aggregate.tsv}: a dossier sample's model-chosen calls
	 * (free-text {@code search_runbooks} queries and the like) are often unrecorded, so a fully replayed dossier still
	 * shows about 0.91 (the T4 smoke check), while an explorer sample that lost most of its evidence falls well below.
	 */
	static final double COVERED_THRESHOLD = 0.8;

	/** The cloud route's price per million prompt and completion tokens, for the estimate only. */
	static final double PROMPT_USD_PER_MILLION = 0.10;

	static final double COMPLETION_USD_PER_MILLION = 0.50;

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final String NONE = "-";

	private final ModelRouter router;

	private final LoopSettings settings;

	private final IncidentRepository incidents;

	private final JdbcClient admin;

	private final Path recordings;

	private final Path toolsList;

	private final ReplayCases cases;

	/** How a decision sample writes its dossier; explorer and dossier samples ignore it. */
	private final DossierView decisionView;

	/** Each recording, parsed and checked once. */
	private final Map<Recording, Parsed> parsed = new HashMap<>();

	private JsonNode toolsListNode;

	/** A driver whose decision samples write their dossier as the tables, the default view. */
	ReplayDriver(ModelRouter router, LoopSettings settings, IncidentRepository incidents, JdbcClient admin,
			Path recordings, Path toolsList) {
		this(router, settings, incidents, admin, recordings, toolsList, DossierView.TABLES);
	}

	/**
	 * @param admin the agent database, to open the incident and read it back as the full view does
	 * @param recordings the replay directory: {@code recordings.tsv} and the files it names
	 * @param toolsList the recorded tool listing
	 * @param decisionView how a decision sample writes its dossier
	 */
	ReplayDriver(ModelRouter router, LoopSettings settings, IncidentRepository incidents, JdbcClient admin,
			Path recordings, Path toolsList, DossierView decisionView) {
		this.router = router;
		this.decisionView = decisionView;
		this.settings = settings;
		this.incidents = incidents;
		this.admin = admin;
		this.recordings = recordings;
		this.toolsList = toolsList;
		this.cases = ReplayCases.read(recordings);
	}

	/**
	 * Refuses, before any model call, a property of the other kind of run, so a run is never other than the one asked
	 * for: decision mode replays the cases of {@code replay.split}, each {@code replay.repeats} times, and takes no
	 * {@code replay.samples}; explorer and dossier mode take {@code replay.samples} per fault, and neither of the other
	 * two, nor the dossier view ({@link DossierView#PROPERTY}), which only decision mode reads.
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
		List<Recording> cases = decisionCases(split, faults);
		cases.forEach(this::parsed);
		for (Recording recording : cases) {
			for (int n = 1; n <= repeats; n++) {
				each.accept(decisionSample(recording, n));
			}
		}
	}

	/**
	 * Runs repeat {@code n} of decision case {@code recording} to its end, over that recording alone: it holds every
	 * call the dossier makes, so pooling it with others would only add other incidents' outputs.
	 * @throws IllegalArgumentException when the recording lacks its start, symptom or window
	 * @throws IllegalStateException when the loop did not run the incident
	 */
	Sample decisionSample(Recording recording, int n) {
		Parsed parsed = parsed(recording);
		return run(parsed, List.of(parsed.incident()), AgentMode.DECISION, recording.split(), n);
	}

	/**
	 * Runs one sample in {@code mode} to its end over the {@code served} recordings, opened on {@code primary}'s
	 * symptom and window with "now" at its start, and scores it against its truth. A decision sample records the run's
	 * view; the others, which read none, record {@code -}.
	 */
	private Sample run(Parsed primary, List<JsonNode> served, AgentMode mode, String split, int n) {
		String fault = primary.recording().fault();
		ReplayLabTools tools = new ReplayLabTools(toolsList(), served);
		Clock clock = Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), primary.startedAt()));

		UUID id = open(primary.symptom(), primary.sinceMinutes(), clock.instant());
		IncidentStatus status;
		long start = System.nanoTime();
		try (AgentLoop loop = new AgentLoop(this.incidents, this.router, tools, this.settings, clock, mode,
				this.decisionView)) {
			status = loop.run(id);
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		if (status == null) {
			throw new IllegalStateException("the loop did not run incident " + id + " of " + fault);
		}

		ObjectNode incident = incident(id);
		JsonNode report = incident.path("report");
		String reported = report.isObject()
				? report.path("rootCauseService").asString("") + "|" + report.path("rootCauseCategory").asString("")
				: "none";
		ArrayNode dossier = dossierCalls(incident);
		int dossierUnrecorded = 0;
		for (JsonNode call : dossier) {
			if (call.path("output").asString("").startsWith(ReplayLabTools.NOT_RECORDED)) {
				dossierUnrecorded++;
			}
		}
		String route = this.router.route(ModelTask.ACT).route().value();
		String truth = primary.recording().truth();
		String view = mode == AgentMode.DECISION ? this.decisionView.value() : NONE;
		return new Sample(fault, mode, route, split, view, primary.recording().stem(), n, status, truth, reported,
				verdict(status, reported, truth), tools.recordedCalls(), tools.unrecordedCalls(), tools.coverage(),
				tools.conflicts(), total(incident, "promptTokens"), total(incident, "completionTokens"), seconds,
				dossier.size(), dossierUnrecorded, incident);
	}

	/** The fault's first recording made in {@code mode}, if any. */
	private static Optional<Parsed> first(List<Parsed> ofFault, AgentMode mode) {
		return ofFault.stream().filter(recording -> recording.recording().mode().equals(mode.value())).findFirst();
	}

	/** The rule of {@code agent_verdict} in {@code lib.sh}: a completed report naming the truth, else a miss. */
	static String verdict(IncidentStatus status, String reported, String truth) {
		return status == IncidentStatus.COMPLETED && truth != null && !truth.isEmpty() && truth.equals(reported)
				? "MATCH" : "MISS";
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
	 * One line per fault, mode, route, split and view, in the samples' order: the distinct recordings the samples
	 * opened on, the samples, matches out of samples, mean coverage, the covered samples (coverage at least
	 * {@link #COVERED_THRESHOLD}) and the matches among them, the samples whose {@code DOSSIER} step made calls and had
	 * none unrecorded out of samples, tokens and, on the cloud route, the estimated cost at
	 * {@link #PROMPT_USD_PER_MILLION} and {@link #COMPLETION_USD_PER_MILLION}. With repeats, the samples outnumber the
	 * recordings, which are the independent observations.
	 */
	static List<String> aggregate(List<Sample> samples) {
		Map<String, List<Sample>> groups = new LinkedHashMap<>();
		for (Sample sample : samples) {
			groups.computeIfAbsent(
					String.join("\t", sample.fault(), sample.mode().value(), sample.route(), sample.split(),
							sample.view()),
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
				.filter(sample -> sample.dossierCalls() > 0 && sample.dossierUnrecorded() == 0)
				.count();
			Long prompt = sum(group.stream().map(Sample::promptTokens).toList());
			Long completion = sum(group.stream().map(Sample::completionTokens).toList());
			boolean cloud = group.get(0).route().equals(AgentRoute.CLOUD.value());
			String cost = !cloud || (prompt == null && completion == null) ? NONE
					: format("%.4f", (orZero(prompt) * PROMPT_USD_PER_MILLION
							+ orZero(completion) * COMPLETION_USD_PER_MILLION) / 1_000_000);
			lines.add(String.join("\t", key, String.valueOf(recordings), String.valueOf(group.size()),
					matches + "/" + group.size(), format("%.2f", coverage), String.valueOf(covered.size()),
					matchesCovered + "/" + covered.size(), dossierCovered + "/" + group.size(), shown(prompt),
					shown(completion), cost));
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
	 * case; {@code view} is the dossier view of a decision sample, {@code -} for the others; {@code recording} is the
	 * stem of the recording it opened on. {@code recorded}, {@code unrecorded},
	 * {@code coverage} and {@code conflicts} are the sample's {@link ReplayLabTools} counters; {@code dossierCalls} and
	 * {@code dossierUnrecorded} count the {@code DOSSIER} step's calls and those no recording held; a token count is
	 * null when no step counted it.
	 */
	record Sample(String fault, AgentMode mode, String route, String split, String view, String recording, int n,
			IncidentStatus status, String truth, String reported, String verdict, int recorded, int unrecorded,
			double coverage, int conflicts, Integer promptTokens, Integer completionTokens, double seconds,
			int dossierCalls, int dossierUnrecorded, ObjectNode incident) {

		/** The sample's line of {@code summary.tsv}, in {@link #SUMMARY_HEADER}'s columns. */
		String summaryLine() {
			return String.join("\t", this.fault, this.mode.value(), this.route, String.valueOf(this.n),
					this.status.name(), this.truth, this.reported, this.verdict, String.valueOf(this.recorded),
					String.valueOf(this.unrecorded), format("%.2f", this.coverage), String.valueOf(this.conflicts),
					shown(this.promptTokens), shown(this.completionTokens), format("%.1f", this.seconds), this.split,
					this.recording, this.view);
		}

	}

}
