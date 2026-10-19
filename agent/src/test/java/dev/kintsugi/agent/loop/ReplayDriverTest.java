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
 * T4), with a scripted model over the committed recordings: no lab and no real model. {@link ReplayRunTest} runs the
 * same pipeline with the route's real model, on demand only.
 */
class ReplayDriverTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	private static final Path F1_DOSSIER = ReplayDriver.DEFAULT_RECORDINGS
		.resolve("F1/verify-agent-20261019T173217Z-dossier-cloud-F1.json");

	private static final String F1_TRUTH = "gateway-mock|DEPENDENCY_LATENCY";

	private static final Instant F1_DOSSIER_START = Instant.parse("2026-10-19T17:44:08.969873Z");

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
		// Dossier gathering replays fully: every one of its calls is in the recordings.
		assertThat(sample.dossierCalls()).isEqualTo(29);
		assertThat(sample.dossierUnrecorded()).isZero();
		assertThat(sample.recorded()).isEqualTo(29);
		assertThat(sample.unrecorded()).isZero();
		assertThat(sample.coverage()).isEqualTo(1.0);

		List<String> summary = Files.readAllLines(this.out.resolve("summary.tsv"));
		assertThat(summary).hasSize(2);
		assertThat(summary.get(0)).isEqualTo(ReplayDriver.SUMMARY_HEADER);
		String[] line = summary.get(1).split("\t", -1);
		assertThat(line).hasSize(15);
		assertThat(List.of(line).subList(0, 11)).containsExactly("F1", "dossier", "local", "1", "COMPLETED",
				"gateway-mock|DEPENDENCY_LATENCY", "UNKNOWN|UNKNOWN", "MISS", "29", "0", "1.00");
		assertThat(line[11]).isEqualTo(String.valueOf(sample.conflicts()));
		assertThat(line[12]).isEqualTo(String.valueOf(sample.promptTokens()));
		assertThat(line[14]).matches("\\d+\\.\\d");

		JsonNode incident = JSON.readTree(this.out.resolve("F1-dossier-1.json").toFile());
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
		ObjectNode dossier = (ObjectNode) JSON.readTree(F1_DOSSIER.toFile());
		ObjectNode explorer = dossier.deepCopy();
		ArrayNode explorerCalls = dossierCallsOf(explorer);
		String sharedCall = explorerCalls.get(0).path("tool").asString() + " " + explorerCalls.get(0).path("args");
		((ObjectNode) explorerCalls.get(0)).put("output", "the explorer run's output");
		setDossierCalls(explorer, explorerCalls);
		Path replay = recordingsDir(List.of(line("explorer", "F1/explorer.json"), line("dossier", "F1/dossier.json")),
				List.of(explorer, dossier), List.of("F1/explorer.json", "F1/dossier.json"));
		this.model.quiet().quiet().text(unknownReport());

		ReplayDriver.Sample sample = driverOver(replay).sample("F1", AgentMode.DOSSIER, 1);

		String expected = dossierCallsOf(dossier).get(0).path("output").asString();
		JsonNode served = null;
		for (JsonNode call : dossierCallsOf(sample.incident())) {
			if ((call.path("tool").asString() + " " + call.path("args")).equals(sharedCall)) {
				served = call;
			}
		}
		assertThat(served).as("the shared call in the sample's dossier").isNotNull();
		assertThat(served.path("output").asString()).isEqualTo(expected);
		assertThat(sample.conflicts()).isEqualTo(1);
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
				"F2\texplorer\tcloud\t1/2\t0.75\t1\t1/1\t0/2\t2000000\t200000\t0.3000");
	}

	@Test
	void theLocalRouteHasNoCost() {
		ReplayDriver.Sample local = new ReplayDriver.Sample("F3", AgentMode.DOSSIER, "local", 1,
				IncidentStatus.TIMED_OUT, "order-service|CONFIGURATION", "none", "MISS", 29, 0, 1.0, 0, null, null, 1.0,
				29, 0, JSON.createObjectNode());

		assertThat(ReplayDriver.aggregate(List.of(local))).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F3\tdossier\tlocal\t0/1\t1.00\t1\t0/1\t1/1\t-\t-\t-");
	}

	@Test
	void theAggregateCountsTheCoveredSamplesAndTheirMatches() {
		List<ReplayDriver.Sample> samples = List.of(dossierSample("MATCH", 0.9, 0), dossierSample("MISS", 0.8, 0),
				dossierSample("MATCH", 0.5, 2), new ReplayDriver.Sample("F1", AgentMode.DOSSIER, "local", 4,
						IncidentStatus.COMPLETED, F1_TRUTH, F1_TRUTH, "MATCH", 4, 1, 0.8, 0, null, null, 1.0, 0, 0,
						JSON.createObjectNode()));

		// Covered (coverage >= 0.8): samples 1, 2 and 4, two of them matches; a fully recorded dossier: samples 1 and 2.
		assertThat(ReplayDriver.COVERED_THRESHOLD).isEqualTo(0.8);
		assertThat(ReplayDriver.aggregate(samples)).containsExactly(ReplayDriver.AGGREGATE_HEADER,
				"F1\tdossier\tlocal\t3/4\t0.75\t3\t2/3\t2/4\t-\t-\t-");
	}

	private static ReplayDriver.Sample dossierSample(String verdict, double coverage, int dossierUnrecorded) {
		return new ReplayDriver.Sample("F1", AgentMode.DOSSIER, "local", 1, IncidentStatus.COMPLETED, F1_TRUTH,
				verdict.equals("MATCH") ? F1_TRUTH : "UNKNOWN|UNKNOWN", verdict, 29, 3, coverage, 0, null, null, 1.0, 29,
				dossierUnrecorded, JSON.createObjectNode());
	}

	private static String line(String mode, String file) {
		return String.join("\t", "F1", mode, "cloud", F1_TRUTH, file, "00000000-0000-0000-0000-000000000000");
	}

	/** A replay directory under the test's temp dir: a manifest of {@code lines} and the {@code recordings} at {@code files}. */
	private Path recordingsDir(List<String> lines, List<ObjectNode> recordings, List<String> files) throws IOException {
		Path replay = Files.createTempDirectory(this.out, "replay");
		List<String> manifest = new ArrayList<>();
		manifest.add("fault\tmode\troute\ttruth\tfile\tincident");
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
		return new ReplayDriver.Sample("F2", AgentMode.EXPLORER, "cloud", 1, IncidentStatus.COMPLETED,
				"gateway-mock|DEPENDENCY_REJECTING", "gateway-mock|DEPENDENCY_REJECTING", verdict, 1, 1, coverage, 0,
				prompt, completion, 1.0, 0, 0, JSON.createObjectNode());
	}

}
