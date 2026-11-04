package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static dev.kintsugi.agent.loop.ScriptedChatModel.call;
import static dev.kintsugi.agent.loop.ScriptedChatModel.unknownReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.lab.ReplayLabTools;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.rules.RulesCandidate;
import dev.kintsugi.agent.rules.RulesVersion;

/**
 * The replay driver's per-sample pipeline (agent spec, "Replay of recorded tool outputs"; odd/tasks/replay-harness.md
 * T4), its decision-mode cases and the listing of {@link ReplayCases} (odd/tasks/decision-instrument.md T3, T3r), and
 * the dossier view a decision run records (odd/tasks/service-assessment.md T1), with a scripted model over the
 * committed recordings: no lab and no real model. {@link ReplayRunTest} runs the same pipeline with the route's real model, on
 * demand only.
 */
class ReplayDriverTest {

	/**
	 * The resource calls of a lab dossier, 3 per service (resource-facts T4, ADR-021): the lab recordings predate them,
	 * so a replay answers each as not recorded (AC2).
	 */
	private static final int RESOURCE_CALLS = 12;

	/**
	 * The coverage of a lab dossier replayed: its 33 recorded calls of 33, since the coverage leaves the resource calls
	 * out (resource-facts T4, the human's choice), while {@code unrecorded} still counts them.
	 */
	private static final double LAB_COVERAGE = 1.0;

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	/** The settings columns of the tests' local route: granite, no think field, a 512-token report in 5 s. */
	private static final String LOCAL_COLUMNS = "\tgranite4.1:8b\tdefault\t512\t5s";

	/** The policy's last two columns of a run with no write executed and no call refused (ADR-027). */
	private static final String NO_POLICY_EVENT = "\t0\t0";

	/**
	 * The end of a summary line on that route with no candidates, no ranked alternatives and no policy event: the
	 * settings, the two empty columns, then the policy's two counts.
	 */
	private static final String LOCAL_LINE_END = LOCAL_COLUMNS + "\t\t" + NO_POLICY_EVENT;

	/** The settings of a hand-made local sample, as the route of application.yaml would record them. */
	private static final ReplayDriver.Settings LOCAL = new ReplayDriver.Settings("granite4.1:8b", "default", "512",
			"600s");

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

	/** A driver over the committed recordings whose decision runs write their dossier in {@code view}. */
	private ReplayDriver driverIn(DossierView view) {
		return driverIn(view, DecisionReport.PLAIN);
	}

	/**
	 * A driver over the committed recordings whose decision runs write their dossier in {@code view} and ask for
	 * {@code report}.
	 */
	private ReplayDriver driverIn(DossierView view, DecisionReport report) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return new ReplayDriver(task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS),
				new LoopSettings(15, Duration.ofMinutes(30), Duration.ofSeconds(5)),
				new IncidentRepository(AgentDatabase.asAgentApp(), JSON), AgentDatabase.admin(),
				ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST, view, report);
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
		// The recording holds 33 of the dossier's calls, the 4 latency calls included (dossier-latency T3), and none of
		// its 12 resource calls, which it predates (resource-facts T4).
		assertThat(sample.dossierCalls()).isEqualTo(33 + RESOURCE_CALLS);
		assertThat(sample.dossierUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.dossierResourceUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.recorded()).isEqualTo(33);
		assertThat(sample.unrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.coverage()).isEqualTo(LAB_COVERAGE);

		List<String> summary = Files.readAllLines(this.out.resolve("summary.tsv"));
		assertThat(summary).hasSize(2);
		assertThat(summary.get(0)).isEqualTo(ReplayDriver.SUMMARY_HEADER);
		String[] line = summary.get(1).split("\t", -1);
		assertThat(line).hasSize(27);
		assertThat(List.of(line).subList(0, 11)).containsExactly("F1", "dossier", "local", "1", "COMPLETED",
				"gateway-mock|DEPENDENCY_LATENCY", "UNKNOWN|UNKNOWN", "MISS", "33", "12", "1.00");
		assertThat(line[11]).isEqualTo(String.valueOf(sample.conflicts()));
		assertThat(line[12]).isEqualTo(String.valueOf(sample.promptTokens()));
		assertThat(line[14]).matches("\\d+\\.\\d");
		// A dossier sample is no case of a split, so it has none; it names the recording it opened on, which also names
		// its file.
		assertThat(List.of(line).subList(15, 17)).containsExactly("-", F1_DOSSIER_STEM);
		// Only decision mode reads the dossier view: a dossier sample has none.
		assertThat(line[17]).isEqualTo("-");
		// The route's settings come last (qwen-dev T3): the model, its thinking, the report's cap and its deadline.
		assertThat(List.of(line).subList(19, 23)).containsExactly("granite4.1:8b", "default", "512", "5s");
		// The rules' candidates come after them (hybrid T4): only rules and hybrid samples have any.
		assertThat(line[23]).isEmpty();
		// The model's ranked alternatives come next (ranked-report T2; ADR-025): this report has none.
		assertThat(line[24]).isEmpty();
		// The policy's counts come last (ADR-027): the dossier's calls ran no write and none was refused.
		assertThat(List.of(line).subList(25, 27)).containsExactly("0", "0");
		assertThat(summary.get(0)).endsWith(
				"\tview\treport\tmodel\tthink\treportCap\treportTimeout\tcandidates\tranked\triskyExecuted\tpolicyRefused");

		JsonNode incident = JSON.readTree(this.out.resolve("F1-dossier-" + F1_DOSSIER_STEM + "-1.json").toFile());
		assertThat(incident.path("status").asString()).isEqualTo("COMPLETED");
		assertThat(incident.path("mode").asString()).isEqualTo("dossier");
		assertThat(incident.path("sinceMinutes").asInt()).isEqualTo(12);
		assertThat(incident.path("steps").get(0).path("kind").asString()).isEqualTo("DOSSIER");
		assertThat(incident.path("report").path("rootCauseService").asString()).isEqualTo("UNKNOWN");
		// The incident file records what the policy did, as its summary line does (ADR-027).
		assertThat(incident.path("riskyExecuted").asInt(-1)).isZero();
		assertThat(incident.path("policyRefused").asInt(-1)).isZero();
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
		// Two stress recordings of F1 hold the dossier's first call with different outputs; the explorer one is listed
		// first. Explorer and dossier samples pool the fault's stress recordings, every mode.
		ObjectNode dossier = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		ObjectNode explorer = dossier.deepCopy();
		ArrayNode explorerCalls = dossierCallsOf(explorer);
		String sharedCall = explorerCalls.get(0).path("tool").asString() + " " + explorerCalls.get(0).path("args");
		((ObjectNode) explorerCalls.get(0)).put("output", "the explorer run's output");
		setDossierCalls(explorer, explorerCalls);
		Path replay = recordingsDir(
				List.of(line("F1", "explorer", "F1/explorer.json", "stress"),
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
	void explorerAndDossierSamplesPoolOnlyTheStressRecordings() throws IOException {
		// A dev and a holdout recording of F1, listed before its stress one, hold the dossier's first call with other
		// outputs, as the decision-mode imports will: a dossier sample is served the stress recording alone, so the
		// imports change nothing and no holdout output reaches it.
		ObjectNode stress = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		JsonNode first = dossierCallsOf(stress).get(0);
		String sharedCall = first.path("tool").asString() + " " + first.path("args");
		List<ObjectNode> imported = new ArrayList<>();
		for (String split : List.of("dev", "holdout")) {
			ObjectNode recording = stress.deepCopy();
			ArrayNode calls = dossierCallsOf(recording);
			((ObjectNode) calls.get(0)).put("output", "the " + split + " run's output");
			setDossierCalls(recording, calls);
			imported.add(recording);
		}
		Path replay = recordingsDir(
				List.of(line("F1", "dossier", "F1/dev.json", "dev"),
						line("F1", "decision", "F1/holdout.json", "holdout"),
						line("F1", "dossier", "F1/stress.json", "stress"), line("F2", "decision", "F2/dev.json", "dev")),
				List.of(imported.get(0), imported.get(1), stress),
				List.of("F1/dev.json", "F1/holdout.json", "F1/stress.json"));
		this.model.quiet().quiet().text(unknownReport());
		ReplayDriver driver = driverOver(replay);

		ReplayDriver.Sample sample = driver.sample("F1", AgentMode.DOSSIER, 1);

		assertThat(sample.recording()).isEqualTo("stress");
		assertThat(servedOutput(sample, sharedCall)).isEqualTo(first.path("output").asString());
		assertThat(sample.conflicts()).isZero();
		assertThat(sample.coverage()).isEqualTo(LAB_COVERAGE);
		assertThat(sample.split()).isEqualTo("-");
		// A fault with no stress recording has no explorer or dossier sample: it fails before any model call.
		int calls = this.model.calls();
		assertThatThrownBy(() -> driver.sample("F2", AgentMode.EXPLORER, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("F2")
			.hasMessageContaining("stress");
		assertThat(this.model.calls()).as("model calls").isEqualTo(calls);
	}

	@Test
	void aDecisionCaseReplaysItsDossierRecordingWithNoUnrecordedCall() {
		this.model.text(unknownReport());

		// The committed manifest's six rows are the stress split; F1's explorer recording, listed first, is no case.
		List<ReplayCases.Recording> cases = this.driver.decisionCases("stress", List.of("F1"));
		assertThat(cases).extracting(ReplayCases.Recording::file)
			.containsExactly("F1/" + F1_DOSSIER_STEM + ".json");
		ReplayDriver.Sample sample = this.driver.decisionSample(cases.get(0), 1);

		assertThat(sample.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(sample.truth()).isEqualTo(F1_TRUTH);
		// The case's recording opens the sample: its start is "now".
		Instant startedAt = Instant.parse(sample.incident().path("startedAt").asString());
		assertThat(Duration.between(F1_DOSSIER_START, startedAt).abs()).isLessThan(Duration.ofMinutes(1));
		assertThat(sample.incident().path("mode").asString()).isEqualTo("decision");
		assertThat(sample.dossierCalls()).isEqualTo(33 + RESOURCE_CALLS);
		assertThat(sample.dossierUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.dossierResourceUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.recorded()).isEqualTo(33);
		assertThat(sample.unrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.coverage()).isEqualTo(LAB_COVERAGE);
		// The report is the sample's only model call.
		assertThat(this.model.calls()).isEqualTo(1);
		// The view of the run, the tables by default, is recorded with the sample.
		assertThat(sample.view()).hasValue(DossierView.TABLES);
		// So is its report, the plain one by default (service-assessment T2r).
		assertThat(sample.report()).hasValue(DecisionReport.PLAIN);
		assertThat(sample.summaryLine()).startsWith("F1\tdecision\tlocal\t1\tCOMPLETED\t")
			.endsWith("\tstress\t" + F1_DOSSIER_STEM + "\ttables\tplain" + LOCAL_LINE_END);
	}

	/**
	 * The ranked column (ranked-report T2; ADR-025): the model's alternatives, each {@code service|category}, in its
	 * order, joined by ';', after the candidates; the reported column is still the first cause alone.
	 */
	@Test
	void aPlainReportsAlternativesAreTheLastColumnAndAFailedSampleHasNone() {
		this.model.text(ScriptedChatModel.rankedReport("gateway-mock", "DEPENDENCY_LATENCY",
				List.of("payment-service|DEPENDENCY_LATENCY", "order-service|CONFIGURATION"),
				ScriptedChatModel.dossierEvidence(1)));

		ReplayDriver.Sample sample = this.driver.decisionSample(this.driver.decisionCases("stress", List.of("F1")).get(0),
				1);

		assertThat(sample.reported()).isEqualTo(F1_TRUTH);
		assertThat(sample.rankedColumn()).isEqualTo("payment-service|DEPENDENCY_LATENCY;order-service|CONFIGURATION");
		assertThat(sample.summaryLine()).endsWith(LOCAL_COLUMNS + "\t\t" + sample.rankedColumn() + NO_POLICY_EVENT);
		// A sample whose incident holds no report, as a failed run's, writes none.
		assertThat(decisionSample("dev", "a", "MISS").rankedColumn()).isEmpty();
	}

	/**
	 * An abstaining report's suspects (ADR-025, amendment 2026-11-03) are stored and written to the ranked column, as
	 * any alternatives are; the scorer, not the driver, gives an abstaining row its empty ranking.
	 */
	@Test
	void anAbstainingReportsSuspectsAreTheRankedColumn() {
		this.model.text(ScriptedChatModel.rankedReport("UNKNOWN", "UNKNOWN",
				List.of("payment-service|DEPENDENCY_LATENCY", "order-service|CONFIGURATION")));

		ReplayDriver.Sample sample = this.driver.decisionSample(this.driver.decisionCases("stress", List.of("F1")).get(0),
				1);

		assertThat(sample.reported()).isEqualTo("UNKNOWN|UNKNOWN");
		assertThat(sample.rankedColumn()).isEqualTo("payment-service|DEPENDENCY_LATENCY;order-service|CONFIGURATION");
		assertThat(sample.summaryLine()).endsWith(LOCAL_COLUMNS + "\t\t" + sample.rankedColumn() + NO_POLICY_EVENT);
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

		List<ReplayCases.Recording> cases = driver.decisionCases("dev", List.of("F1"));
		ReplayDriver.Sample first = driver.decisionSample(cases.get(0), 1);
		ReplayDriver.Sample second = driver.decisionSample(cases.get(1), 1);

		assertThat(servedOutput(first, sharedCall))
			.isEqualTo(dossierCallsOf(dossier).get(0).path("output").asString());
		assertThat(servedOutput(second, sharedCall)).isEqualTo("the decision run's output");
		for (ReplayDriver.Sample sample : List.of(first, second)) {
			assertThat(sample.conflicts()).as("conflicts").isZero();
			assertThat(sample.recorded()).as("recorded calls").isEqualTo(33);
			assertThat(sample.coverage()).as("coverage").isEqualTo(LAB_COVERAGE);
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
		assertThat(driver.decisionCases("dev", List.of("F2", "F1"))).extracting(ReplayCases.Recording::file)
			.containsExactly("F2/decision-dev.json", "F1/dossier-dev.json", "F1/decision-dev.json");
		assertThat(driver.decisionCases("holdout", List.of("F2"))).extracting(ReplayCases.Recording::file)
			.containsExactly("F2/decision-holdout.json");
		assertThat(driver.decisionCases("stress", List.of("F1"))).extracting(ReplayCases.Recording::split)
			.containsExactly("stress");
	}

	@Test
	void anUnknownSplitOrAFaultWithNoCaseFailsBeforeAnyModelCall() throws IOException {
		ReplayDriver driver = driverOver(recordingsDir(List.of(line("F1", "decision", "F1/a.json", "dev"),
				line("F2", "decision", "F2/b.json", "holdout")), List.of(), List.of()));

		assertThatThrownBy(() -> driver.decisionCases("hold-out", List.of("F1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'hold-out'")
			.hasMessageContaining("dev, holdout, stress, probe or holdout-2");
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
	void theEighthColumnIsTheScheduleCaseOfAHoldout2Recording() throws IOException {
		// holdout-2 (ADR-026): every row names its schedule case in the eighth column, a healthy control's class is
		// "healthy" and its truth NONE|NONE (ADR-015). A row of seven columns, as the RCAEval converter writes, has none.
		Path replay = recordingsDir(List.of(line("F1", "decision", "F1/a.json", "dev") + "\t",
				"healthy\tdecision\tlocal\tNONE|NONE\thealthy/b.json\tid-b\tholdout-2\t12",
				"F2\tdecision\tlocal\t" + TRUTHS.get("F2") + "\tF2/c.json\tid-c\tholdout-2\t3",
				line("F3", "decision", "F3/d.json", "dev")), List.of(), List.of());
		ReplayCases cases = ReplayCases.read(replay);

		assertThat(cases.decisionCases("holdout-2", List.of("healthy", "F2")))
			.extracting(ReplayCases.Recording::file, ReplayCases.Recording::truth, ReplayCases.Recording::scheduleCase)
			.containsExactly(tuple("healthy/b.json", "NONE|NONE", "12"), tuple("F2/c.json", TRUTHS.get("F2"), "3"));
		assertThat(cases.decisionCases("dev", List.of("F1", "F3"))).extracting(ReplayCases.Recording::scheduleCase)
			.containsExactly("", "");
	}

	@Test
	void theCommittedManifestHasTheCaseColumnEmptyOnEveryRowBeforeHoldout2() throws IOException {
		List<String> lines = Files.readAllLines(ReplayDriver.DEFAULT_RECORDINGS.resolve("recordings.tsv"));

		assertThat(lines.get(0)).isEqualTo("fault\tmode\troute\ttruth\tfile\tincident\tsplit\tcase");
		assertThat(lines.subList(1, lines.size())).allSatisfy(row -> assertThat(row.split("\t", -1)).hasSize(8));
		ReplayCases cases = ReplayCases.read(ReplayDriver.DEFAULT_RECORDINGS);
		for (String split : List.of("dev", "holdout")) {
			assertThat(cases.decisionCases(split, List.of("F1", "F2", "F3"))).as(split)
				.hasSize(15)
				.extracting(ReplayCases.Recording::scheduleCase)
				.containsOnly("");
		}
	}

	@Test
	void theListingPrintsTheCasesTheDriverReplays() throws IOException {
		Path replay = recordingsDir(List.of(line("F1", "decision", "F1/a.json", "dev"),
				line("F1", "explorer", "F1/b.json", "dev"), line("F2", "dossier", "F2/c.json", "dev"),
				line("F1", "dossier", "F1/d.json", "holdout"), line("F1", "dossier", "F1/e.json", "dev")), List.of(),
				List.of());
		List<String> replayed = driverOver(replay).decisionCases("dev", List.of("F2", "F1"))
			.stream()
			.map(recording -> recording.fault() + "\t" + recording.file())
			.toList();

		// What replay-agent.sh counts before Maven, with the faults written as replay.faults takes them.
		Listing listing = listing(replay.toString(), "dev", "F2, F1");

		assertThat(listing.status()).isZero();
		assertThat(listing.lines()).containsExactlyElementsOf(replayed)
			.containsExactly("F2\tF2/c.json", "F1\tF1/a.json", "F1\tF1/e.json");
		assertThat(listing.error()).isEmpty();
		// ReplayCases runs from its source alone, so it names the case modes as the manifest records them.
		assertThat(ReplayCases.CASE_MODES).containsExactly(AgentMode.DECISION.value(), AgentMode.DOSSIER.value());
	}

	@Test
	void theListingRefusesWhatTheDriverRefusesWithStatusTwo() throws IOException {
		Path replay = recordingsDir(List.of(line("F1", "decision", "F1/a.json", "dev")), List.of(), List.of());
		Path unknownSplit = recordingsDir(List.of(line("F1", "decision", "F1/a.json", "test")), List.of(), List.of());

		// 2 is replay-agent.sh's status for a bad option; the reason is the driver's.
		assertThat(listing(replay.toString(), "hold-out", "F1"))
			.isEqualTo(new Listing(2, List.of(), "unknown split 'hold-out': choose dev, holdout, stress, probe or holdout-2"));
		assertThat(listing(replay.toString(), "dev", "F1,F3")).isEqualTo(new Listing(2, List.of(),
				"no decision or dossier recording of F3 in split dev of " + replay.resolve("recordings.tsv")));
		Listing refused = listing(unknownSplit.toString(), "dev", "F1");
		assertThat(refused.status()).isEqualTo(2);
		assertThat(refused.error()).startsWith("a manifest line has the split 'test', not dev, holdout, stress, probe or holdout-2");
		assertThat(listing(replay.toString(), "dev").status()).isEqualTo(2);
	}

	@Test
	void aPropertyOfTheOtherKindOfRunIsRefused() {
		Map<String, String> samples = Map.of("replay.samples", "3");

		// Decision mode replays the cases of a split: replay.split and replay.repeats, never replay.samples.
		assertThatThrownBy(() -> ReplayDriver.refuseIgnoredProperties(true, samples::get))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("replay.samples")
			.hasMessageContaining("replay.repeats");
		ReplayDriver.refuseIgnoredProperties(true, Map.of("replay.split", "holdout", "replay.repeats", "2")::get);
		// Explorer and dossier mode sample faults: replay.samples, never replay.split or replay.repeats.
		for (String property : List.of("replay.split", "replay.repeats")) {
			assertThatThrownBy(() -> ReplayDriver.refuseIgnoredProperties(false, Map.of(property, "2")::get))
				.as(property)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(property);
		}
		ReplayDriver.refuseIgnoredProperties(false, samples::get);
		ReplayDriver.refuseIgnoredProperties(false, Map.<String, String>of()::get);
		// Only decision mode reads the dossier view (service-assessment T1).
		Map<String, String> view = Map.of(DossierView.PROPERTY, "sheets");
		assertThatThrownBy(() -> ReplayDriver.refuseIgnoredProperties(false, view::get))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("kintsugi.agent.decision.view");
		ReplayDriver.refuseIgnoredProperties(true, view::get);
		// Nor the report (service-assessment T2r).
		Map<String, String> report = Map.of(DecisionReport.PROPERTY, "assessed");
		assertThatThrownBy(() -> ReplayDriver.refuseIgnoredProperties(false, report::get))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.decision.report does not apply to this mode: only decision mode reads it; "
					+ "explorer and dossier mode always ask for the plain report");
		ReplayDriver.refuseIgnoredProperties(true, report::get);
		// Nor the rules version (resource-facts T5): the rules route and the hybrid report are decision mode's.
		Map<String, String> rules = Map.of(RulesVersion.PROPERTY, "v2");
		assertThatThrownBy(() -> ReplayDriver.refuseIgnoredProperties(false, rules::get))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.rules does not apply to this mode: only decision mode reads it, on the rules "
					+ "route and in the hybrid report");
		ReplayDriver.refuseIgnoredProperties(true, rules::get);
	}

	@Test
	void theRulesVersionIsReadFromItsPropertyV1ByDefaultAndAnyOtherValueFails() {
		assertThat(ReplayDriver.rules(Map.<String, String>of()::get)).isEqualTo(RulesVersion.V1);
		assertThat(ReplayDriver.rules(Map.of(RulesVersion.PROPERTY, "v2")::get)).isEqualTo(RulesVersion.V2);
		assertThatThrownBy(() -> ReplayDriver.rules(Map.of(RulesVersion.PROPERTY, "v9")::get))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.rules must be v1 or v2, got \"v9\"");
	}

	@Test
	void aRulesSampleRecordsItsRulesVersionAsItsModel() throws IOException {
		// The lab recordings predate the resource calls, so Config 0R gives Config 0's verdict and candidates there.
		ReplayDriver v1 = ReplayDriver.rulesOnly(ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST,
				DossierView.TABLES, DecisionReport.PLAIN, LAB, RulesVersion.V1);
		ReplayDriver v2 = ReplayDriver.rulesOnly(ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST,
				DossierView.TABLES, DecisionReport.PLAIN, LAB, RulesVersion.V2);
		ReplayCases.Recording r1 = v2.decisionCases("dev", List.of("F1")).get(0);

		ReplayDriver.Sample config0 = v1.rulesSample(r1, 1);
		ReplayDriver.Sample config0r = v2.rulesSample(r1, 1);
		ReplayDriver.write(this.out, config0r);

		assertThat(config0r.reported()).isEqualTo(config0.reported()).isEqualTo(F1_TRUTH);
		assertThat(config0r.candidates()).isEqualTo(config0.candidates());
		assertThat(config0.settings()).isEqualTo(ReplayDriver.Settings.RULES);
		assertThat(config0r.settings()).isEqualTo(new ReplayDriver.Settings("rules-v2", "-", "-", "-"));
		String[] line = Files.readAllLines(this.out.resolve("summary.tsv")).get(1).split("\t", -1);
		assertThat(List.of(line).subList(19, 23)).containsExactly("rules-v2", "-", "-", "-");
		assertThat(config0r.incident().path("model").asString()).isEqualTo("rules-v2");
	}

	@Test
	void aDecisionRunInTheSheetsViewRecordsItsViewAndADossierSampleHasNone() {
		this.model.text(unknownReport()).quiet().quiet().text(unknownReport());
		ReplayDriver driver = driverIn(DossierView.SHEETS);

		ReplayDriver.Sample decision = driver.decisionSample(driver.decisionCases("stress", List.of("F1")).get(0), 1);
		ReplayDriver.Sample dossier = driver.sample("F1", AgentMode.DOSSIER, 1);

		assertThat(decision.view()).hasValue(DossierView.SHEETS);
		assertThat(dossierMessage(decision)).contains("# Evidence dossier, by service\n")
			.doesNotContain("## Service status (incident window)");
		assertThat(decision.dossierUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(decision.summaryLine()).endsWith("\tstress\t" + F1_DOSSIER_STEM + "\tsheets\tplain" + LOCAL_LINE_END);
		// Dossier mode reads no view: it shows the tables, and its sample records none.
		assertThat(dossier.view()).isEmpty();
		assertThat(dossier.report()).isEmpty();
		assertThat(dossierMessage(dossier)).contains("## Service status (incident window)")
			.doesNotContain("# Evidence dossier, by service");
		assertThat(ReplayDriver.aggregate(List.of(decision, dossier))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdecision\tlocal\tstress\tsheets\tplain\t1\t1\t0/1\t1.00\t1\t0/1\t1/1\t10\t5\t-" + LOCAL_COLUMNS,
				"F1\tdossier\tlocal\t-\t-\t-\t1\t1\t0/1\t1.00\t1\t0/1\t1/1\t30\t15\t-" + LOCAL_COLUMNS);
	}

	@Test
	void aNullViewIsRefusedWhenTheDriverIsBuilt() {
		assertThatThrownBy(() -> driverIn(null)).isInstanceOf(NullPointerException.class)
			.hasMessage("decisionView must not be null: DossierView.TABLES is the default view");
		assertThat(this.model.calls()).as("model calls").isZero();
	}

	@Test
	void anAssessedRunAsksForTheAssessedReportAndRecordsIt() {
		// A plain report fails the assessed check twice: the sample still runs, and its lines name the report.
		this.model.text(unknownReport()).text(unknownReport());
		ReplayDriver driver = driverIn(DossierView.SHEETS, DecisionReport.ASSESSED);

		ReplayDriver.Sample decision = driver.decisionSample(driver.decisionCases("stress", List.of("F1")).get(0), 1);

		assertThat(decision.report()).hasValue(DecisionReport.ASSESSED);
		assertThat(this.model.prompts()).hasSize(2)
			.allSatisfy(prompt -> assertThat(((StructuredOutputChatOptions) prompt.getOptions()).getOutputSchema())
				.isEqualTo(Schemas.assessedReport(LAB)));
		assertThat(decision.summaryLine()).endsWith("\tstress\t" + F1_DOSSIER_STEM + "\tsheets\tassessed" + LOCAL_LINE_END);
		assertThat(ReplayDriver.aggregate(List.of(decision)).get(1))
			.startsWith("F1\tdecision\tlocal\tstress\tsheets\tassessed\t1\t1\t0/1\t");
	}

	/**
	 * A hybrid run (hybrid T4; ADR-019) writes the candidates its report task listed, the list the loop computed from
	 * the gathered dossier: the same as the rules' on that recording, since both read the same recorded calls.
	 */
	@Test
	void aHybridRunRecordsTheCandidatesItsReportListed() {
		// UNKNOWN|UNKNOWN is a valid hybrid answer, so the report is the sample's one model call.
		this.model.text(ScriptedChatModel.unknownHybridReport());
		ReplayDriver driver = driverIn(DossierView.TABLES, DecisionReport.HYBRID);
		ReplayCases.Recording f1 = driver.decisionCases("stress", List.of("F1")).get(0);

		ReplayDriver.Sample hybrid = driver.decisionSample(f1, 1);
		ReplayDriver.Sample rules = ReplayDriver
			.rulesOnly(ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST, DossierView.TABLES,
					DecisionReport.PLAIN)
			.rulesSample(f1, 1);

		assertThat(this.model.calls()).isEqualTo(1);
		assertThat(hybrid.report()).hasValue(DecisionReport.HYBRID);
		assertThat(hybrid.reported()).isEqualTo("UNKNOWN|UNKNOWN");
		assertThat(hybrid.candidates()).isNotEmpty().isEqualTo(rules.candidates());
		// Its candidates are those of the run's rules version, v1 by default, which its incident names.
		assertThat(hybrid.incident().path("rules").asString()).isEqualTo("v1");
		assertThat(hybrid.summaryLine())
			.endsWith("\tstress\t" + F1_DOSSIER_STEM + "\ttables\thybrid" + LOCAL_COLUMNS + "\t"
					+ rules.candidatesColumn() + "\t" + NO_POLICY_EVENT);
		// The aggregate keeps its columns: the candidates are per sample.
		assertThat(ReplayDriver.aggregate(List.of(hybrid)).get(1)).endsWith(LOCAL_COLUMNS);
	}

	@Test
	void theCandidatesColumnJoinsThePairsInOrder() {
		List<RulesCandidate> candidates = List.of(new RulesCandidate("gateway-mock", "DEPENDENCY_LATENCY", "R-DOWN",
				List.of()), new RulesCandidate("payment-service", "DEPENDENCY_REJECTING", "R-DOWN", List.of()));
		ReplayDriver.Sample rules = new ReplayDriver.Sample("F1", AgentMode.DECISION, ReplayDriver.RULES_ROUTE, "dev",
				"r", Optional.of(DossierView.TABLES), Optional.of(DecisionReport.PLAIN), 1, IncidentStatus.COMPLETED,
				F1_TRUTH, F1_TRUTH, "MATCH", 33, 0, 1.0, 0, 0, 0, 0.1, 33, 0, JSON.createObjectNode(),
				ReplayDriver.Settings.RULES, candidates);

		assertThat(rules.candidatesColumn())
			.isEqualTo("gateway-mock|DEPENDENCY_LATENCY;payment-service|DEPENDENCY_REJECTING");
		// A hand-made sample's incident records no policy counts, so its last two columns are "-" (ADR-027).
		assertThat(rules.summaryLine()).endsWith("\trules-v1\t-\t-\t-\t" + rules.candidatesColumn() + "\t\t-\t-");
		assertThat(decisionSample("dev", "a", "MATCH").candidatesColumn()).isEmpty();
	}

	@Test
	void theAssessedReportWithTheTablesIsRefusedBeforeAnyDecisionSample() {
		// Only decision mode applies the report (AgentMode.reportFrom), so the driver is built and the pair is refused
		// when a decision run starts, before its first model call.
		ReplayDriver driver = driverIn(DossierView.TABLES, DecisionReport.ASSESSED);
		String needsSheets = "kintsugi.agent.decision.report=assessed needs kintsugi.agent.decision.view=sheets, got "
				+ "tables: the assessed report's checks read the service that owns each line of the sheets";
		assertThatThrownBy(() -> driver.decisionSamples("stress", List.of("F1"), 1, sample -> {
		})).isInstanceOf(IllegalArgumentException.class).hasMessage(needsSheets);
		ReplayCases.Recording recording = driver.decisionCases("stress", List.of("F1")).get(0);
		assertThatThrownBy(() -> driver.decisionSample(recording, 1)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(needsSheets);
		assertThatThrownBy(() -> driverIn(DossierView.SHEETS, null)).isInstanceOf(NullPointerException.class)
			.hasMessage(AgentLoop.NULL_REPORT);
		assertThat(this.model.calls()).as("model calls").isZero();
	}

	@Test
	void explorerAndDossierSamplesIgnoreTheAssessedReportWithTheTables() {
		// Neither mode applies the report, so the pair refuses nothing there: the sample asks for the plain report.
		this.model.quiet().quiet().text(unknownReport());
		ReplayDriver driver = driverIn(DossierView.TABLES, DecisionReport.ASSESSED);

		ReplayDriver.Sample dossier = driver.sample("F1", AgentMode.DOSSIER, 1);

		assertThat(dossier.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(dossier.view()).isEmpty();
		assertThat(dossier.report()).isEmpty();
		assertThat(this.model.prompts().getLast().getOptions()).isInstanceOfSatisfying(
				StructuredOutputChatOptions.class,
				options -> assertThat(options.getOutputSchema()).isEqualTo(Schemas.report(AgentMode.DOSSIER, LAB)));
	}

	/** The dossier message the sample's model read: its {@code DOSSIER} step's summary. */
	private static String dossierMessage(ReplayDriver.Sample sample) {
		for (JsonNode step : sample.incident().path("steps")) {
			if (step.path("kind").asString("").equals("DOSSIER")) {
				return step.path("summary").asString();
			}
		}
		throw new AssertionError("no DOSSIER step");
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
		assertThat(summary.get(4)).startsWith("F1\tdecision\tlocal\t2\t").endsWith("\tdev\tb\ttables\tplain" + LOCAL_LINE_END);
		// The aggregate tells the 2 distinct recordings from the 4 samples. The coverage and the fully recorded dossiers
		// leave the resource calls out, so all 4 are covered and fully recorded (resource-facts T4).
		List<String> aggregate = ReplayDriver.aggregate(samples);
		assertThat(aggregate).hasSize(2);
		assertThat(aggregate.get(1)).startsWith("F1\tdecision\tlocal\tdev\ttables\tplain\t2\t4\t0/4\t1.00\t4\t0/4\t4/4\t");
	}

	@Test
	void aRecordingWithoutItsStartSymptomOrWindowFailsBeforeAnyModelCall() throws IOException {
		List<Consumer<ObjectNode>> breaks = List.of(recording -> recording.remove("startedAt"),
				recording -> recording.remove("symptom"), recording -> recording.put("sinceMinutes", 0));
		for (Consumer<ObjectNode> broken : breaks) {
			ObjectNode recording = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
			broken.accept(recording);
			Path replay = recordingsDir(List.of(line("F1", "dossier", "F1/broken.json", "stress")), List.of(recording),
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
				"F2\texplorer\tcloud\t-\t-\t-\t1\t2\t1/2\t0.75\t1\t1/1\t0/2\t2000000\t200000\t0.3000"
						+ "\tclaude-haiku-5-5\toff\t768\t90s");
	}

	/**
	 * Config 0 (ADR-016; odd/tasks/rules-baseline.md T3): a rules sample gathers the dossier from the recording, as a
	 * decision sample's loop does, and diagnoses it with {@code RulesDiagnosis}, with no router, no model and no
	 * database. F1 r1 is the first dev case of F1.
	 */
	@Test
	void theCoverageLeavesTheResourceCallsOutAndTheUnrecordedCountKeepsThem() {
		// resource-facts T4: one recorded call, one unrecorded call, and two resource calls, one of them unrecorded.
		ReplayDriver.CoverageCounter counter = new ReplayDriver.CoverageCounter(new ReplayLabToolsStub());

		counter.call("service_restarts", "{\"sinceMinutes\":10}");
		counter.call("search_logs", "{\"service\":\"order-service\",\"sinceMinutes\":10}");
		counter.call("query_metric", "{\"metric\":\"processor_usage\",\"service\":\"order-service\",\"sinceMinutes\":20}");
		counter.call("query_metric", "{\"metric\":\"open_files\",\"service\":\"order-service\",\"sinceMinutes\":20}");
		counter.call("query_metric", "{\"metric\":\"latency_avg\",\"service\":\"order-service\",\"sinceMinutes\":20}");

		assertThat(counter.coverage(3, 2)).isEqualTo(2.0 / 3);
		assertThat(ReplayDriver.CoverageCounter.coverage(0, 0, 0, 0)).isEqualTo(1.0);
		assertThat(ReplayDriver.CoverageCounter.coverage(0, 2, 2, 2)).isEqualTo(1.0);
	}

	/**
	 * Answers {@code search_logs} and {@code open_files} as not recorded, every other call from a recording.
	 */
	private static final class ReplayLabToolsStub implements dev.kintsugi.agent.lab.LabTools {

		@Override
		public dev.kintsugi.agent.lab.ToolResult call(String tool, String jsonArgs) {
			boolean unrecorded = tool.equals("search_logs") || jsonArgs.contains("open_files");
			return new dev.kintsugi.agent.lab.ToolResult(tool, jsonArgs,
					unrecorded ? ReplayLabTools.NOT_RECORDED + tool : "{\"outcome\":\"OK\"}",
					unrecorded ? dev.kintsugi.agent.lab.ToolOutcome.CLIENT_ERROR : dev.kintsugi.agent.lab.ToolOutcome.OK, 0);
		}

		@Override
		public List<org.springframework.ai.tool.ToolCallback> toolsFor(dev.kintsugi.agent.lab.Phase phase) {
			return List.of();
		}

	}

	@Test
	void aRulesSampleDiagnosesTheRecordedDossierWithNoModel() throws IOException {
		ReplayDriver driver = ReplayDriver.rulesOnly(ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST,
				DossierView.TABLES, DecisionReport.PLAIN);
		ReplayCases.Recording r1 = driver.decisionCases("dev", List.of("F1")).get(0);
		assertThat(r1.stem()).isEqualTo("verify-agent-20261024T162555Z-r1-F1");

		ReplayDriver.Sample sample = driver.rulesSample(r1, 1);
		ReplayDriver.write(this.out, sample);

		assertThat(this.model.calls()).as("model calls").isZero();
		assertThat(sample.route()).isEqualTo(ReplayDriver.RULES_ROUTE).isEqualTo("rules");
		assertThat(sample.mode()).isEqualTo(AgentMode.DECISION);
		assertThat(sample.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(sample.truth()).isEqualTo(F1_TRUTH);
		assertThat(sample.reported()).isEqualTo(F1_TRUTH);
		assertThat(sample.verdict()).isEqualTo("MATCH");
		assertThat(sample.promptTokens()).isZero();
		assertThat(sample.completionTokens()).isZero();
		// The gatherer's calls are the only calls, so they are the dossier's, and the recording holds every one but the
		// resource calls, which it predates (resource-facts T4, AC2).
		assertThat(sample.unrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.coverage()).isEqualTo(LAB_COVERAGE);
		assertThat(sample.dossierCalls()).isEqualTo(sample.recorded() + RESOURCE_CALLS);
		assertThat(sample.recorded()).isPositive();
		assertThat(sample.dossierUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(sample.dossierResourceUnrecorded()).isEqualTo(RESOURCE_CALLS);

		String[] line = Files.readAllLines(this.out.resolve("summary.tsv")).get(1).split("\t", -1);
		assertThat(line).hasSize(27);
		assertThat(List.of(line).subList(0, 8)).containsExactly("F1", "decision", "rules", "1", "COMPLETED", F1_TRUTH,
				F1_TRUTH, "MATCH");
		// The rules' candidates on the same dossier (hybrid T4), their verdict first, written service|category in order
		// and joined by ';'; the reported column is still the verdict alone.
		assertThat(sample.candidates()).isNotEmpty()
			.first()
			.satisfies(first -> assertThat(first.service() + "|" + first.category()).isEqualTo(F1_TRUTH));
		assertThat(line[23]).isEqualTo(sample.candidatesColumn())
			.startsWith(F1_TRUTH)
			.matches("[^|;\t]+\\|[A-Z_]+(;[^|;\t]+\\|[A-Z_]+){0,4}");
		// The rules write no ranked alternatives: only a model's plain report has them (ranked-report T2; ADR-025).
		assertThat(line[24]).isEmpty();
		assertThat(sample.rankedColumn()).isEmpty();
		// The gatherer's calls passed the policy gate (ADR-027): no write ran, none was refused.
		assertThat(List.of(line).subList(25, 27)).containsExactly("0", "0");
		assertThat(List.of(line).subList(12, 14)).containsExactly("0", "0");
		assertThat(List.of(line).subList(15, 19)).containsExactly("dev", r1.stem(), "tables", "plain");
		// Config 0 calls no model: its model is the rules' version, and it has no thinking, cap or deadline.
		assertThat(List.of(line).subList(19, 23)).containsExactly("rules-v1", "-", "-", "-");

		JsonNode incident = JSON
			.readTree(this.out.resolve("F1-decision-verify-agent-20261024T162555Z-r1-F1-1.json").toFile());
		assertThat(incident.path("id").asString()).isNotBlank();
		assertThat(incident.path("mode").asString()).isEqualTo("decision");
		assertThat(incident.path("route").asString()).isEqualTo("rules");
		assertThat(incident.path("model").asString()).isEqualTo(ReplayDriver.RULES_MODEL).isEqualTo("rules-v1");
		assertThat(incident.path("status").asString()).isEqualTo("COMPLETED");
		assertThat(incident.path("report").path("rootCauseService").asString()).isEqualTo("gateway-mock");
		assertThat(incident.path("report").path("rootCauseCategory").asString()).isEqualTo("DEPENDENCY_LATENCY");
		assertThat(incident.path("rule").asString()).isEqualTo("R-DOWN");
		assertThat(incident.path("facts").isArray()).isTrue();
		assertThat(incident.path("facts").size()).isPositive();
		assertThat(incident.path("view").asString()).isEqualTo("tables");
		assertThat(incident.path("decisionReport").asString()).isEqualTo("plain");
	}

	@Test
	void aRulesRunReplaysEveryCaseOfTheSplitAndRecordsItsView() {
		ReplayDriver driver = ReplayDriver.rulesOnly(ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST,
				DossierView.SHEETS, DecisionReport.PLAIN);
		List<ReplayDriver.Sample> samples = new ArrayList<>();

		driver.rulesSamples("dev", List.of("F1", "F3"), 2, samples::add);

		// 5 dev cases of F1 and 5 of F3, each twice; the rules read the record, so the view only labels the sample.
		assertThat(samples).hasSize(20).allSatisfy(sample -> {
			assertThat(sample.route()).isEqualTo("rules");
			assertThat(sample.split()).isEqualTo("dev");
			assertThat(sample.view()).hasValue(DossierView.SHEETS);
			assertThat(sample.promptTokens()).isZero();
			assertThat(sample.completionTokens()).isZero();
		});
		assertThat(samples.get(0).reported()).isEqualTo(samples.get(1).reported());
		assertThat(this.model.calls()).as("model calls").isZero();
	}

	@Test
	void aRulesOnlyDriverRunsNoModelSample() {
		ReplayDriver driver = ReplayDriver.rulesOnly(ReplayDriver.DEFAULT_RECORDINGS, ReplayDriver.DEFAULT_TOOLS_LIST,
				DossierView.TABLES, DecisionReport.PLAIN);
		ReplayCases.Recording r1 = driver.decisionCases("dev", List.of("F1")).get(0);

		assertThatThrownBy(() -> driver.sample("F1", AgentMode.DOSSIER, 1)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("rules-only");
		assertThatThrownBy(() -> driver.decisionSample(r1, 1)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("rules-only");
		assertThat(AgentDatabase.admin().sql("SELECT count(*) FROM incidents").query(Long.class).single()).isZero();
	}

	/**
	 * A run on an overridden local route (qwen-dev T3, AC2) records the settings its report call ran with on every
	 * line: the model, the thinking setting, the report's cap and its deadline, after the columns of every run before.
	 */
	@Test
	void aSampleRecordsTheOverriddenRouteSettingsItRanWith() {
		this.model.text(unknownReport());
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(1800));
		ReplayDriver driver = driver(task -> new OllamaRoute("qwen3.5:9b", this.model, 0.0, 8192, timeouts,
				new NumPredict(384, 160, 2048), true), ReplayDriver.DEFAULT_RECORDINGS);

		ReplayDriver.Sample decision = driver.decisionSample(driver.decisionCases("stress", List.of("F1")).get(0), 1);

		assertThat(decision.settings()).isEqualTo(new ReplayDriver.Settings("qwen3.5:9b", "on", "2048", "1800s"));
		assertThat(decision.summaryLine())
			.endsWith("\tstress\t" + F1_DOSSIER_STEM + "\ttables\tplain\tqwen3.5:9b\ton\t2048\t1800s\t\t" + NO_POLICY_EVENT);
		assertThat(ReplayDriver.aggregate(List.of(decision)).get(1)).endsWith("\tqwen3.5:9b\ton\t2048\t1800s");
		// The report call carried the thinking setting and the cap the line names.
		OllamaChatOptions sent = (OllamaChatOptions) this.model.prompts().get(0).getOptions();
		assertThat(sent.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.ENABLED);
		assertThat(sent.getNumPredict()).isEqualTo(2048);
	}

	/** Samples of the same fault and split under different route settings are separate aggregate lines. */
	@Test
	void theAggregateKeepsEachRouteSettingApart() {
		ReplayDriver.Sample granite = decisionSample("dev", "a", "MATCH");
		ReplayDriver.Sample qwen = new ReplayDriver.Sample("F1", AgentMode.DECISION, "local", "dev", "a",
				Optional.of(DossierView.TABLES), Optional.of(DecisionReport.PLAIN), 1, IncidentStatus.TIMED_OUT,
				F1_TRUTH, "none", "MISS", 33, 0, 1.0, 0, null, null, 1.0, 33, 0, JSON.createObjectNode(),
				new ReplayDriver.Settings("qwen3.5:9b", "off", "1024", "900s"), List.of());

		assertThat(ReplayDriver.aggregate(List.of(granite, qwen))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdecision\tlocal\tdev\ttables\tplain\t1\t1\t1/1\t1.00\t1\t1/1\t1/1\t-\t-\t-\tgranite4.1:8b\tdefault\t512\t600s",
				"F1\tdecision\tlocal\tdev\ttables\tplain\t1\t1\t0/1\t1.00\t1\t0/1\t1/1\t-\t-\t-\tqwen3.5:9b\toff\t1024\t900s");
		assertThat(ReplayDriver.AGGREGATE_HEADER).endsWith("\tcostUsd\tmodel\tthink\treportCap\treportTimeout");
	}

	@Test
	void theRulesRouteCostsNothingAndSaysSo() {
		ReplayDriver.Sample rules = new ReplayDriver.Sample("F1", AgentMode.DECISION, ReplayDriver.RULES_ROUTE, "dev",
				"r", Optional.of(DossierView.TABLES), Optional.of(DecisionReport.PLAIN), 1, IncidentStatus.COMPLETED,
				F1_TRUTH, F1_TRUTH, "MATCH", 33, 0, 1.0, 0, 0, 0, 0.1, 33, 0, JSON.createObjectNode(),
				ReplayDriver.Settings.RULES, List.of());

		assertThat(ReplayDriver.aggregate(List.of(rules))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdecision\trules\tdev\ttables\tplain\t1\t1\t1/1\t1.00\t1\t1/1\t1/1\t0\t0\t0.0000\trules-v1\t-\t-\t-");
	}

	@Test
	void theLocalRouteHasNoCost() {
		ReplayDriver.Sample local = new ReplayDriver.Sample("F3", AgentMode.DOSSIER, "local", "-", "r",
				Optional.empty(), Optional.empty(), 1, IncidentStatus.TIMED_OUT, "order-service|CONFIGURATION", "none", "MISS", 29, 0,
				1.0, 0, null, null, 1.0, 29, 0, JSON.createObjectNode(), LOCAL, List.of());

		assertThat(ReplayDriver.aggregate(List.of(local))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F3\tdossier\tlocal\t-\t-\t-\t1\t1\t0/1\t1.00\t1\t0/1\t1/1\t-\t-\t-\tgranite4.1:8b\tdefault\t512\t600s");
	}

	@Test
	void theAggregateCountsTheCoveredSamplesAndTheirMatches() {
		List<ReplayDriver.Sample> samples = List.of(dossierSample("MATCH", 0.9, 0), dossierSample("MISS", 0.8, 0),
				dossierSample("MATCH", 0.5, 2),
				new ReplayDriver.Sample("F1", AgentMode.DOSSIER, "local", "-", "r", Optional.empty(), Optional.empty(), 4,
						IncidentStatus.COMPLETED, F1_TRUTH, F1_TRUTH, "MATCH", 4, 1, 0.8, 0, null, null, 1.0, 0, 0,
						JSON.createObjectNode(), LOCAL, List.of()));

		// Covered (coverage >= 0.8): samples 1, 2 and 4, two of them matches; a fully recorded dossier: samples 1 and 2.
		assertThat(ReplayDriver.COVERED_THRESHOLD).isEqualTo(0.8);
		assertThat(ReplayDriver.aggregate(samples)).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdossier\tlocal\t-\t-\t-\t1\t4\t3/4\t0.75\t3\t2/3\t2/4\t-\t-\t-\tgranite4.1:8b\tdefault\t512\t600s");
	}

	@Test
	void aDossierWhoseOnlyUnrecordedCallsAreResourceCallsIsFullyRecorded() {
		// resource-facts T4: 12 unrecorded calls, all resource calls, then 13, one of them another call.
		ReplayDriver.Sample resourcesOnly = dossierSample("MATCH", 1.0, RESOURCE_CALLS).withResourceUnrecorded(RESOURCE_CALLS);
		ReplayDriver.Sample oneMore = dossierSample("MATCH", 1.0, RESOURCE_CALLS + 1)
			.withResourceUnrecorded(RESOURCE_CALLS);

		assertThat(resourcesOnly.dossierUnrecorded()).isEqualTo(RESOURCE_CALLS);
		assertThat(ReplayDriver.aggregate(List.of(resourcesOnly, oneMore))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdossier\tlocal\t-\t-\t-\t1\t2\t2/2\t1.00\t2\t2/2\t1/2\t-\t-\t-\tgranite4.1:8b\tdefault\t512\t600s");
	}

	@Test
	void theAggregateCountsTheDistinctRecordingsOfEachSplit() {
		List<ReplayDriver.Sample> samples = List.of(decisionSample("dev", "a", "MATCH"),
				decisionSample("dev", "a", "MISS"), decisionSample("dev", "b", "MATCH"),
				decisionSample("holdout", "c", "MATCH"));

		assertThat(ReplayDriver.aggregate(samples)).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdecision\tlocal\tdev\ttables\tplain\t2\t3\t2/3\t1.00\t3\t2/3\t3/3\t-\t-\t-\tgranite4.1:8b\tdefault\t512\t600s",
				"F1\tdecision\tlocal\tholdout\ttables\tplain\t1\t1\t1/1\t1.00\t1\t1/1\t1/1\t-\t-\t-\tgranite4.1:8b\tdefault\t512\t600s");
	}

	private static ReplayDriver.Sample dossierSample(String verdict, double coverage, int dossierUnrecorded) {
		return new ReplayDriver.Sample("F1", AgentMode.DOSSIER, "local", "-", "r", Optional.empty(), Optional.empty(), 1,
				IncidentStatus.COMPLETED, F1_TRUTH, verdict.equals("MATCH") ? F1_TRUTH : "UNKNOWN|UNKNOWN", verdict, 29,
				3, coverage, 0, null, null, 1.0, 29, dossierUnrecorded, JSON.createObjectNode(), LOCAL, List.of());
	}

	private static ReplayDriver.Sample decisionSample(String split, String recording, String verdict) {
		return new ReplayDriver.Sample("F1", AgentMode.DECISION, "local", split, recording,
				Optional.of(DossierView.TABLES), Optional.of(DecisionReport.PLAIN), 1, IncidentStatus.COMPLETED, F1_TRUTH,
				verdict.equals("MATCH") ? F1_TRUTH : "UNKNOWN|UNKNOWN", verdict, 33, 0, 1.0, 0, null, null, 1.0, 33, 0,
				JSON.createObjectNode(), LOCAL, List.of());
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

	/** {@link ReplayCases#list} on {@code args}: its status, the lines it printed, and its error output. */
	private static Listing listing(String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int status = ReplayCases.list(List.of(args), new PrintStream(out, true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8));
		return new Listing(status, out.toString(StandardCharsets.UTF_8).lines().toList(),
				err.toString(StandardCharsets.UTF_8).strip());
	}

	private record Listing(int status, List<String> lines, String error) {
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
		return new ReplayDriver.Sample("F2", AgentMode.EXPLORER, "cloud", "-", "r", Optional.empty(), Optional.empty(), 1,
				IncidentStatus.COMPLETED, "gateway-mock|DEPENDENCY_REJECTING", "gateway-mock|DEPENDENCY_REJECTING",
				verdict, 1, 1, coverage, 0, prompt, completion, 1.0, 0, 0, JSON.createObjectNode(),
				new ReplayDriver.Settings("claude-haiku-5-5", "off", "768", "90s"), List.of());
	}

}
