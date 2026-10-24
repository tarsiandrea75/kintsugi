package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.ScriptedChatModel.call;
import static dev.kintsugi.agent.loop.ScriptedChatModel.unknownReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;

/**
 * The replay driver's per-sample pipeline (agent spec, "Replay of recorded tool outputs"; odd/tasks/replay-harness.md
 * T4) and its decision-mode cases (odd/tasks/decision-instrument.md T3), with a scripted model over the committed
 * recordings: no lab and no real model. {@link ReplayRunTest} runs the same pipeline with the route's real model, on
 * demand only.
 */
class ReplayDriverTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	private static final Path F1_DOSSIER = ReplayDriver.DEFAULT_RECORDINGS
		.resolve("F1/verify-agent-20261019T214629Z-F1.json");

	private static final String F1_TRUTH = "gateway-mock|DEPENDENCY_LATENCY";

	private static final Map<String, String> TRUTHS = Map.of("F1", F1_TRUTH, "F2",
			"gateway-mock|DEPENDENCY_REJECTING", "F3", "order-service|CONFIGURATION");

	private static final String F1_DOSSIER_STEM = "verify-agent-20261019T214629Z-F1";

	private static final Instant F1_DOSSIER_START = Instant.parse("2026-10-19T21:48:59.713752Z");

	private final ScriptedChatModel model = new ScriptedChatModel();

	@TempDir
	Path out;

	private ReplayDriver driver;

	@BeforeAll
	static void migrate() {
		AgentDatabase.migrate();
	}

	@BeforeEach
	void setUp() {
		AgentDatabase.empty();
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		ModelRouter router = task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS);
		this.driver = driver(router, ReplayDriver.DEFAULT_RECORDINGS);
	}

	private ReplayDriver driver(ModelRouter router, Path recordings) {
		return new ReplayDriver(router, new LoopSettings(15, Duration.ofMinutes(30), Duration.ofSeconds(5)),
				new IncidentRepository(AgentDatabase.asAgentApp(), JSON), AgentDatabase.admin(), recordings,
				ReplayDriver.DEFAULT_TOOLS_LIST);
	}

	private ReplayDriver driverOver(Path recordings) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return driver(task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), recordings);
	}

	@Test
	void aDossierSampleIsScoredAgainstTheRecordedTruthWithItsCoverage() throws IOException {
		this.model.quiet().quiet().text(unknownReport());

		ReplayDriver.Sample sample = this.driver.sample("F1", AgentMode.DOSSIER, 1);
		ReplayDriver.write(this.out, sample);

		assertThat(sample.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(sample.truth()).isEqualTo("gateway-mock|DEPENDENCY_LATENCY");
		assertThat(sample.reported()).isEqualTo("UNKNOWN|UNKNOWN");
		assertThat(sample.verdict()).isEqualTo("MISS");
		// The recording holds the dossier's 33 calls, the 4 latency calls included (dossier-latency T3).
		assertThat(sample.dossierCalls()).isEqualTo(33);
		assertThat(sample.dossierUnrecorded()).isZero();
		assertThat(sample.recorded()).isEqualTo(33);
		assertThat(sample.unrecorded()).isZero();
		assertThat(sample.coverage()).isEqualTo(1.0);

		List<String> summary = Files.readAllLines(this.out.resolve("summary.tsv"));
		assertThat(summary).hasSize(2);
		assertThat(summary.get(0)).isEqualTo(ReplayDriver.SUMMARY_HEADER);
		String[] line = summary.get(1).split("\t", -1);
		assertThat(line).hasSize(17);
		assertThat(List.of(line).subList(0, 11)).containsExactly("F1", "dossier", "local", "1", "COMPLETED",
				"gateway-mock|DEPENDENCY_LATENCY", "UNKNOWN|UNKNOWN", "MISS", "33", "0", "1.00");
		assertThat(line[11]).isEqualTo(String.valueOf(sample.conflicts()));
		assertThat(line[12]).isEqualTo(String.valueOf(sample.promptTokens()));
		assertThat(line[14]).matches("\\d+\\.\\d");
		// A dossier sample pools the fault's recordings of every split, so it has none; it names the recording it
		// opened on, which also names its file.
		assertThat(List.of(line).subList(15, 17)).containsExactly("-", F1_DOSSIER_STEM);

		JsonNode incident = JSON.readTree(this.out.resolve("F1-dossier-" + F1_DOSSIER_STEM + "-1.json").toFile());
		assertThat(incident.path("status").asString()).isEqualTo("COMPLETED");
		assertThat(incident.path("mode").asString()).isEqualTo("dossier");
		assertThat(incident.path("sinceMinutes").asInt()).isEqualTo(12);
		assertThat(incident.path("steps").get(0).path("kind").asString()).isEqualTo("DOSSIER");
		assertThat(incident.path("report").path("rootCauseService").asString()).isEqualTo("UNKNOWN");
		// "Now" is the recorded incident's start, so the windows match the recorded timestamps.
		Instant startedAt = Instant.parse(incident.path("startedAt").asString());
		assertThat(Duration.between(F1_DOSSIER_START, startedAt).abs()).isLessThan(Duration.ofMinutes(1));
	}

	@Test
	void anExplorerSampleCountsTheCallsNoRecordingHolds() {
		this.model
			.planned(call("status_summary", "{\"table\":\"orders\",\"sinceMinutes\":12}"),
					call("status_summary", "{\"table\":\"payments\",\"sinceMinutes\":97}"))
			.text("")
			.quietPhasesAfterTriage()
			.text(unknownReport());

		ReplayDriver.Sample sample = this.driver.sample("F1", AgentMode.EXPLORER, 2);

		assertThat(sample.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(sample.recorded()).isEqualTo(1);
		assertThat(sample.unrecorded()).isEqualTo(1);
		assertThat(sample.coverage()).isEqualTo(0.5);
		assertThat(sample.dossierCalls()).isZero();
		assertThat(sample.summaryLine()).startsWith("F1\texplorer\tlocal\t2\tCOMPLETED\t");
	}

	@Test
	void aSampleIsServedItsOwnModesRecordingBeforeTheOthers() throws IOException {
		// Two recordings of F1 hold the dossier's first call with different outputs; the explorer one is listed first.
		// They are in different splits: explorer and dossier samples pool every recording of the fault, whatever its
		// split, as before the splits.
		ObjectNode dossier = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		ObjectNode explorer = dossier.deepCopy();
		ArrayNode explorerCalls = dossierCallsOf(explorer);
		String sharedCall = explorerCalls.get(0).path("tool").asString() + " " + explorerCalls.get(0).path("args");
		((ObjectNode) explorerCalls.get(0)).put("output", "the explorer run's output");
		setDossierCalls(explorer, explorerCalls);
		Path replay = recordingsDir(
				List.of(line("F1", "explorer", "F1/explorer.json", "holdout"),
						line("F1", "dossier", "F1/dossier.json", "stress")),
				List.of(explorer, dossier), List.of("F1/explorer.json", "F1/dossier.json"));
		this.model.quiet().quiet().text(unknownReport());

		ReplayDriver.Sample sample = driverOver(replay).sample("F1", AgentMode.DOSSIER, 1);

		String expected = dossierCallsOf(dossier).get(0).path("output").asString();
		assertThat(servedOutput(sample, sharedCall)).isEqualTo(expected);
		assertThat(sample.conflicts()).isEqualTo(1);
		assertThat(sample.split()).isEqualTo("-");
		assertThat(sample.recording()).isEqualTo("dossier");
	}

	@Test
	void aDecisionCaseReplaysItsDossierRecordingWithNoUnrecordedCall() {
		this.model.text(unknownReport());

		// The committed manifest's six rows are the stress split; F1's explorer recording, listed first, is no case.
		List<ReplayDriver.Recording> cases = this.driver.decisionCases("stress", List.of("F1"));
		assertThat(cases).extracting(ReplayDriver.Recording::file)
			.containsExactly("F1/" + F1_DOSSIER_STEM + ".json");
		ReplayDriver.Sample sample = this.driver.decisionSample(cases.get(0), 1);

		assertThat(sample.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(sample.truth()).isEqualTo(F1_TRUTH);
		// The case's recording opens the sample: its start is "now".
		Instant startedAt = Instant.parse(sample.incident().path("startedAt").asString());
		assertThat(Duration.between(F1_DOSSIER_START, startedAt).abs()).isLessThan(Duration.ofMinutes(1));
		assertThat(sample.incident().path("mode").asString()).isEqualTo("decision");
		assertThat(sample.dossierCalls()).isEqualTo(33);
		assertThat(sample.dossierUnrecorded()).isZero();
		assertThat(sample.recorded()).isEqualTo(33);
		assertThat(sample.unrecorded()).isZero();
		assertThat(sample.coverage()).isEqualTo(1.0);
		// The report is the sample's only model call.
		assertThat(this.model.calls()).isEqualTo(1);
		assertThat(sample.summaryLine()).startsWith("F1\tdecision\tlocal\t1\tCOMPLETED\t")
			.endsWith("\tstress\t" + F1_DOSSIER_STEM);
	}

	@Test
	void aDecisionCaseIsServedItsOwnRecordingAlone() throws IOException {
		// Two cases of F1 hold the dossier's first call with different outputs: neither is served the other's.
		ObjectNode dossier = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		ObjectNode decision = dossier.deepCopy();
		ArrayNode decisionCalls = dossierCallsOf(decision);
		String sharedCall = decisionCalls.get(0).path("tool").asString() + " " + decisionCalls.get(0).path("args");
		((ObjectNode) decisionCalls.get(0)).put("output", "the decision run's output");
		setDossierCalls(decision, decisionCalls);
		Path replay = recordingsDir(List.of(line("dossier", "F1/dossier.json"), line("decision", "F1/decision.json")),
				List.of(dossier, decision), List.of("F1/dossier.json", "F1/decision.json"));
		this.model.text(unknownReport()).text(unknownReport());
		ReplayDriver driver = driverOver(replay);

		List<ReplayDriver.Recording> cases = driver.decisionCases("dev", List.of("F1"));
		ReplayDriver.Sample first = driver.decisionSample(cases.get(0), 1);
		ReplayDriver.Sample second = driver.decisionSample(cases.get(1), 1);

		assertThat(servedOutput(first, sharedCall))
			.isEqualTo(dossierCallsOf(dossier).get(0).path("output").asString());
		assertThat(servedOutput(second, sharedCall)).isEqualTo("the decision run's output");
		for (ReplayDriver.Sample sample : List.of(first, second)) {
			assertThat(sample.conflicts()).as("conflicts").isZero();
			assertThat(sample.recorded()).as("recorded calls").isEqualTo(33);
			assertThat(sample.coverage()).as("coverage").isEqualTo(1.0);
		}
		assertThat(List.of(first.recording(), second.recording())).containsExactly("dossier", "decision");
		assertThat(List.of(first.split(), second.split())).containsExactly("dev", "dev");
		// A decision sample by fault would pool the fault's recordings: it is refused, with no model call.
		assertThatThrownBy(() -> driver.sample("F1", AgentMode.DECISION, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("decision");
		assertThat(this.model.calls()).isEqualTo(2);
	}

	@Test
	void theCasesOfASplitAreItsDecisionAndDossierRecordingsOfTheChosenFaults() throws IOException {
		// The files need not exist: cases come from the manifest alone.
		Path replay = recordingsDir(List.of(line("F1", "explorer", "F1/explorer-dev.json", "dev"),
				line("F1", "dossier", "F1/dossier-dev.json", "dev"),
				line("F2", "decision", "F2/decision-holdout.json", "holdout"),
				line("F1", "decision", "F1/decision-dev.json", "dev"),
				line("F2", "decision", "F2/decision-dev.json", "dev"),
				line("F3", "decision", "F3/decision-dev.json", "dev"),
				line("F1", "decision", "F1/decision-stress.json", "stress")), List.of(), List.of());
		ReplayDriver driver = driverOver(replay);

		// One case per recording: the chosen faults in their order, each fault's recordings in manifest order.
		assertThat(driver.decisionCases("dev", List.of("F2", "F1"))).extracting(ReplayDriver.Recording::file)
			.containsExactly("F2/decision-dev.json", "F1/dossier-dev.json", "F1/decision-dev.json");
		assertThat(driver.decisionCases("holdout", List.of("F2"))).extracting(ReplayDriver.Recording::file)
			.containsExactly("F2/decision-holdout.json");
		assertThat(driver.decisionCases("stress", List.of("F1"))).extracting(ReplayDriver.Recording::split)
			.containsExactly("stress");
	}

	@Test
	void anUnknownSplitOrAFaultWithNoCaseFailsBeforeAnyModelCall() throws IOException {
		ReplayDriver driver = driverOver(recordingsDir(List.of(line("F1", "decision", "F1/a.json", "dev"),
				line("F2", "decision", "F2/b.json", "holdout")), List.of(), List.of()));

		assertThatThrownBy(() -> driver.decisionCases("hold-out", List.of("F1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'hold-out'")
			.hasMessageContaining("dev, holdout, stress or probe");
		assertThatThrownBy(() -> driver.decisionCases("holdout", List.of("F1", "F2")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("of F1 in split holdout");
		// A manifest line with no split, or an unknown one, fails the driver as it reads the manifest.
		Path noSplit = recordingsDir(List.of("F1\tdecision\tcloud\t" + F1_TRUTH + "\tF1/a.json\tid"), List.of(),
				List.of());
		assertThatThrownBy(() -> driverOver(noSplit)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("7 columns");
		Path unknownSplit = recordingsDir(List.of(line("F1", "decision", "F1/a.json", "test")), List.of(), List.of());
		assertThatThrownBy(() -> driverOver(unknownSplit)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'test'");
		// Every case of the split is read before the first sample: a broken second case stops the run before the first.
		ObjectNode broken = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		broken.remove("startedAt");
		Path replay = recordingsDir(List.of(line("dossier", "F1/good.json"), line("decision", "F1/broken.json")),
				List.of((ObjectNode) JSON.readTree(F1_DOSSIER.toFile()), broken),
				List.of("F1/good.json", "F1/broken.json"));
		List<ReplayDriver.Sample> samples = new ArrayList<>();
		assertThatThrownBy(() -> driverOver(replay).decisionSamples("dev", List.of("F1"), 1, samples::add))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("F1/broken.json");
		assertThat(samples).isEmpty();
		assertThat(this.model.calls()).as("model calls").isZero();
	}

	@Test
	void eachCaseRunsRepeatsTimesEachSampleWithItsOwnCounters() throws IOException {
		ObjectNode recording = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		Path replay = recordingsDir(List.of(line("dossier", "F1/a.json"), line("decision", "F1/b.json")),
				List.of(recording, recording), List.of("F1/a.json", "F1/b.json"));
		this.model.text(unknownReport()).text(unknownReport()).text(unknownReport()).text(unknownReport());
		List<ReplayDriver.Sample> samples = new ArrayList<>();

		driverOver(replay).decisionSamples("dev", List.of("F1"), 2, samples::add);
		samples.forEach(sample -> ReplayDriver.write(this.out, sample));

		// Case by case, each repeated: 2 recordings x 2 repeats.
		assertThat(samples).extracting(sample -> sample.recording() + "#" + sample.n())
			.containsExactly("a#1", "a#2", "b#1", "b#2");
		assertThat(samples).extracting(ReplayDriver.Sample::recorded).containsExactly(33, 33, 33, 33);
		assertThat(samples).extracting(ReplayDriver.Sample::conflicts).containsExactly(0, 0, 0, 0);
		assertThat(this.model.calls()).isEqualTo(4);
		for (String name : List.of("F1-decision-a-1.json", "F1-decision-a-2.json", "F1-decision-b-1.json",
				"F1-decision-b-2.json")) {
			assertThat(this.out.resolve(name)).as(name).exists();
		}
		List<String> summary = Files.readAllLines(this.out.resolve("summary.tsv"));
		assertThat(summary).hasSize(5);
		assertThat(summary.get(4)).startsWith("F1\tdecision\tlocal\t2\t").endsWith("\tdev\tb");
		// The aggregate tells the 2 distinct recordings from the 4 samples.
		List<String> aggregate = ReplayDriver.aggregate(samples);
		assertThat(aggregate).hasSize(2);
		assertThat(aggregate.get(1)).startsWith("F1\tdecision\tlocal\tdev\t2\t4\t0/4\t1.00\t4\t0/4\t4/4\t");
	}

	@Test
	void aRecordingWithoutItsStartSymptomOrWindowFailsBeforeAnyModelCall() throws IOException {
		List<Consumer<ObjectNode>> breaks = List.of(recording -> recording.remove("startedAt"),
				recording -> recording.remove("symptom"), recording -> recording.put("sinceMinutes", 0));
		for (Consumer<ObjectNode> broken : breaks) {
			ObjectNode recording = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
			broken.accept(recording);
			Path replay = recordingsDir(List.of(line("dossier", "F1/broken.json")), List.of(recording),
					List.of("F1/broken.json"));
			this.model.quiet().quiet().text(unknownReport());

			assertThatThrownBy(() -> driverOver(replay).sample("F1", AgentMode.DOSSIER, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("F1")
				.hasMessageContaining("F1/broken.json");
			assertThat(this.model.calls()).as("model calls").isZero();
			AgentDatabase.empty();
		}
	}

	@Test
	void theVerdictIsAgentVerdictsRule() {
		String truth = "gateway-mock|DEPENDENCY_LATENCY";
		assertThat(ReplayDriver.verdict(IncidentStatus.COMPLETED, truth, truth)).isEqualTo("MATCH");
		assertThat(ReplayDriver.verdict(IncidentStatus.COMPLETED, "gateway-mock|DEPENDENCY_REJECTING", truth))
			.isEqualTo("MISS");
		assertThat(ReplayDriver.verdict(IncidentStatus.TIMED_OUT, truth, truth)).isEqualTo("MISS");
		assertThat(ReplayDriver.verdict(IncidentStatus.COMPLETED, truth, "")).isEqualTo("MISS");
	}

	@Test
	void theAggregateCountsMatchesCoverageAndCost() {
		ReplayDriver.Sample a = sample("MATCH", 1.0, 1_000_000, 100_000);
		ReplayDriver.Sample b = sample("MISS", 0.5, 1_000_000, 100_000);

		List<String> lines = ReplayDriver.aggregate(List.of(a, b));

		assertThat(lines).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				// 2 M prompt tokens at $0.10 and 0.2 M completion tokens at $0.50 per million.
				"F2\texplorer\tcloud\t-\t1\t2\t1/2\t0.75\t1\t1/1\t0/2\t2000000\t200000\t0.3000");
	}

	@Test
	void theLocalRouteHasNoCost() {
		ReplayDriver.Sample local = new ReplayDriver.Sample("F3", AgentMode.DOSSIER, "local", "-", "r", 1,
				IncidentStatus.TIMED_OUT, "order-service|CONFIGURATION", "none", "MISS", 29, 0, 1.0, 0, null, null, 1.0,
				29, 0, JSON.createObjectNode());

		assertThat(ReplayDriver.aggregate(List.of(local))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F3\tdossier\tlocal\t-\t1\t1\t0/1\t1.00\t1\t0/1\t1/1\t-\t-\t-");
	}

	@Test
	void theAggregateCountsTheCoveredSamplesAndTheirMatches() {
		List<ReplayDriver.Sample> samples = List.of(dossierSample("MATCH", 0.9, 0), dossierSample("MISS", 0.8, 0),
				dossierSample("MATCH", 0.5, 2), new ReplayDriver.Sample("F1", AgentMode.DOSSIER, "local", "-", "r", 4,
						IncidentStatus.COMPLETED, F1_TRUTH, F1_TRUTH, "MATCH", 4, 1, 0.8, 0, null, null, 1.0, 0, 0,
						JSON.createObjectNode()));

		// Covered (coverage >= 0.8): samples 1, 2 and 4, two of them matches; a fully recorded dossier: samples 1 and 2.
		assertThat(ReplayDriver.COVERED_THRESHOLD).isEqualTo(0.8);
		assertThat(ReplayDriver.aggregate(samples)).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdossier\tlocal\t-\t1\t4\t3/4\t0.75\t3\t2/3\t2/4\t-\t-\t-");
	}

	@Test
	void theAggregateCountsTheDistinctRecordingsOfEachSplit() {
		List<ReplayDriver.Sample> samples = List.of(decisionSample("dev", "a", "MATCH"),
				decisionSample("dev", "a", "MISS"), decisionSample("dev", "b", "MATCH"),
				decisionSample("holdout", "c", "MATCH"));

		assertThat(ReplayDriver.aggregate(samples)).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdecision\tlocal\tdev\t2\t3\t2/3\t1.00\t3\t2/3\t3/3\t-\t-\t-",
				"F1\tdecision\tlocal\tholdout\t1\t1\t1/1\t1.00\t1\t1/1\t1/1\t-\t-\t-");
	}

	private static ReplayDriver.Sample dossierSample(String verdict, double coverage, int dossierUnrecorded) {
		return new ReplayDriver.Sample("F1", AgentMode.DOSSIER, "local", "-", "r", 1, IncidentStatus.COMPLETED,
				F1_TRUTH, verdict.equals("MATCH") ? F1_TRUTH : "UNKNOWN|UNKNOWN", verdict, 29, 3, coverage, 0, null,
				null, 1.0, 29, dossierUnrecorded, JSON.createObjectNode());
	}

	private static ReplayDriver.Sample decisionSample(String split, String recording, String verdict) {
		return new ReplayDriver.Sample("F1", AgentMode.DECISION, "local", split, recording, 1,
				IncidentStatus.COMPLETED, F1_TRUTH, verdict.equals("MATCH") ? F1_TRUTH : "UNKNOWN|UNKNOWN", verdict, 33,
				0, 1.0, 0, null, null, 1.0, 33, 0, JSON.createObjectNode());
	}

	/** The output the sample's dossier holds for {@code call} (its tool, a space and its arguments). */
	private static String servedOutput(ReplayDriver.Sample sample, String call) {
		JsonNode served = null;
		for (JsonNode each : dossierCallsOf(sample.incident())) {
			if ((each.path("tool").asString() + " " + each.path("args")).equals(call)) {
				served = each;
			}
		}
		assertThat(served).as("the call " + call + " in the sample's dossier").isNotNull();
		return served.path("output").asString();
	}

	/** A manifest line of F1 in the {@code dev} split. */
	private static String line(String mode, String file) {
		return line("F1", mode, file, "dev");
	}

	private static String line(String fault, String mode, String file, String split) {
		return String.join("\t", fault, mode, "cloud", TRUTHS.get(fault), file, "00000000-0000-0000-0000-000000000000",
				split);
	}

	/** A replay directory under the test's temp dir: a manifest of {@code lines} and the {@code recordings} at {@code files}. */
	private Path recordingsDir(List<String> lines, List<ObjectNode> recordings, List<String> files) throws IOException {
		Path replay = Files.createTempDirectory(this.out, "replay");
		List<String> manifest = new ArrayList<>();
		manifest.add("fault\tmode\troute\ttruth\tfile\tincident\tsplit");
		manifest.addAll(lines);
		Files.write(replay.resolve("recordings.tsv"), manifest);
		for (int i = 0; i < files.size(); i++) {
			Path file = replay.resolve(files.get(i));
			Files.createDirectories(file.getParent());
			Files.writeString(file, JSON.writeValueAsString(recordings.get(i)));
		}
		return replay;
	}

	private static ArrayNode dossierCallsOf(JsonNode incident) {
		for (JsonNode step : incident.path("steps")) {
			if (step.path("kind").asString("").equals("DOSSIER")) {
				return (ArrayNode) JSON.readTree(step.path("toolOutput").asString());
			}
		}
		throw new AssertionError("no DOSSIER step");
	}

	private static void setDossierCalls(ObjectNode incident, ArrayNode calls) {
		for (JsonNode step : incident.path("steps")) {
			if (step.path("kind").asString("").equals("DOSSIER")) {
				((ObjectNode) step).put("toolOutput", JSON.writeValueAsString(calls));
			}
		}
	}

	private static ReplayDriver.Sample sample(String verdict, double coverage, int prompt, int completion) {
		return new ReplayDriver.Sample("F2", AgentMode.EXPLORER, "cloud", "-", "r", 1, IncidentStatus.COMPLETED,
				"gateway-mock|DEPENDENCY_REJECTING", "gateway-mock|DEPENDENCY_REJECTING", verdict, 1, 1, coverage, 0,
				prompt, completion, 1.0, 0, 0, JSON.createObjectNode());
	}

}
