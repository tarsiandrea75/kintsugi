package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.Change;
import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.dossier.LineFact;
import dev.kintsugi.agent.dossier.Section;
import dev.kintsugi.agent.lab.ReplayLabTools;
import dev.kintsugi.agent.policy.Policy;
import dev.kintsugi.agent.policy.PolicyTally;

/**
 * The level re-check of stored answers (verification-levels T6; ADR-028): a recorded decision incident is re-checked
 * with today's {@link ReportCheck} only when its dossier re-renders byte for byte and the re-check verifies the evidence
 * the run stored. The fixtures are built here, independently of {@link LevelRecheck}, from committed lab recordings:
 * the dossier gathered as {@link ReplayDriver} gathers it, rendered by today's renderer, and the stored report what the
 * loop stores for the answer.
 */
class LevelRecheckTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final String F1 = "F1/verify-agent-20261024T162555Z-r1-F1.json";

	private static final String F3 = "F3/verify-agent-20261024T162555Z-r1-F3.json";

	private final LevelRecheck recheck = new LevelRecheck(ReplayDriver.DEFAULT_TOOLS_LIST, LAB);

	@Test
	void anAnswerCitingTheCausesChangedLatencyLineIsRecheckedToL3() {
		Fixture f1 = fixture(F1);
		int line = f1.line("gateway-mock", Section.LATENCY, Change.YES);
		ObjectNode incident = f1.incident(answer("gateway-mock", "DEPENDENCY_LATENCY", line));

		LevelRecheck.Rechecked rechecked = this.recheck.recheck(incident, f1.recording);

		assertThat(rechecked.level()).isEqualTo("L3");
		assertThat(rechecked.logOnly()).isFalse();
		assertThat(rechecked.items()).singleElement().satisfies(item -> {
			assertThat(item.line()).isEqualTo(line);
			assertThat(item.owner()).isEqualTo("gateway-mock");
			assertThat(item.section()).isEqualTo("latency");
			assertThat(item.changed()).isEqualTo("YES");
			assertThat(item.causeOwned()).isTrue();
		});
	}

	@Test
	void aCorrectAnswerCitingOnlyTheCausesLogLineStopsAtL2AndIsFlaggedLogOnly() {
		Fixture f3 = fixture(F3);
		int line = f3.line("order-service", Section.LOGS, Change.NOT_COMPARED);
		ObjectNode incident = f3.incident(answer("order-service", "CONFIGURATION", line));

		LevelRecheck.Rechecked rechecked = this.recheck.recheck(incident, f3.recording);

		assertThat(rechecked.level()).isEqualTo("L2");
		assertThat(rechecked.failedChecks()).containsExactly("cause-fact-changed");
		assertThat(rechecked.logOnly()).isTrue();
		assertThat(rechecked.items()).singleElement().satisfies(item -> {
			assertThat(item.section()).isEqualTo("logs");
			assertThat(item.changed()).isEqualTo("NOT_COMPARED");
			assertThat(item.causeOwned()).isTrue();
		});
	}

	@Test
	void aStoredDossierThatDiffersFromTheReRenderedOneIsRefusedNamingTheFirstDifferingLine() {
		Fixture f1 = fixture(F1);
		int line = f1.line("gateway-mock", Section.LATENCY, Change.YES);
		ObjectNode incident = f1.incident(answer("gateway-mock", "DEPENDENCY_LATENCY", line));
		ObjectNode dossierStep = (ObjectNode) incident.path("steps").get(0);
		dossierStep.put("summary", dossierStep.path("summary").asString().replace("D2 - Previous window", "D2 - Earlier window"));

		assertThatThrownBy(() -> this.recheck.recheck(incident, f1.recording))
			.isInstanceOf(LevelRecheck.Refused.class)
			.hasMessageContaining("dossier differs")
			.hasMessageContaining("at message line")
			.hasMessageContaining("D2 - Earlier window");
	}

	@Test
	void aStoredReportWhoseEvidenceTheRecheckDoesNotVerifyIsRefused() {
		Fixture f1 = fixture(F1);
		int line = f1.line("gateway-mock", Section.LATENCY, Change.YES);
		ObjectNode incident = f1.incident(answer("gateway-mock", "DEPENDENCY_LATENCY", line));
		((ObjectNode) incident.path("report").path("evidence").get(0)).put("quote", "another line");

		assertThatThrownBy(() -> this.recheck.recheck(incident, f1.recording))
			.isInstanceOf(LevelRecheck.Refused.class)
			.hasMessageContaining("evidence differs");
	}

	@Test
	void theSummaryRowKeepsEveryColumnButTheLevelAndGainsOneWhenItHasNone() {
		List<String> withLevel = List.of("fault", "split", "level");
		assertThat(LevelRecheck.withLevel(withLevel, "F3\tdev\t-", "L2")).isEqualTo("F3\tdev\tL2");
		List<String> without = List.of("fault", "split");
		assertThat(LevelRecheck.withLevel(without, "F3\tdev", "L2")).isEqualTo("F3\tdev\tL2");
		assertThat(LevelRecheck.header(without)).isEqualTo("fault\tsplit\tlevel");
		assertThat(LevelRecheck.header(withLevel)).isEqualTo("fault\tsplit\tlevel");
	}

	@Test
	void aRunWritesTheSummaryWithTheLevelAndOneDetailRowPerEvidenceItem(@TempDir Path dir) throws IOException {
		Fixture f3 = fixture(F3);
		int line = f3.line("order-service", Section.LOGS, Change.NOT_COMPARED);
		Path in = replayDir(dir.resolve("in"), "dev", f3.incident(answer("order-service", "CONFIGURATION", line)));
		Path out = dir.resolve("out");

		LevelRecheck.Outcome outcome = this.recheck.run(in, ReplayDriver.DEFAULT_RECORDINGS, out);

		assertThat(outcome.passed()).isEqualTo(1);
		assertThat(outcome.refused()).isEmpty();
		List<String> summary = Files.readAllLines(out.resolve("summary.tsv"));
		assertThat(summary.get(0)).isEqualTo(ReplayDriver.SUMMARY_HEADER);
		assertThat(summary.get(1)).isEqualTo(row("dev", "L2"));
		List<String> detail = Files.readAllLines(out.resolve("levels-detail.tsv"));
		assertThat(detail.get(0)).isEqualTo(String.join("\t", LevelRecheck.DETAIL_HEADER));
		assertThat(detail).hasSize(2);
		assertThat(detail.get(1)).startsWith("F3\tverify-agent-20261024T162555Z-r1-F3\t1\t")
			.contains("\tL2\tcause-fact-changed\tyes\t1\tdossier\t" + line + "\torder-service\tlogs\tNOT_COMPARED\tyes");
		assertThat(Files.readString(out.resolve("recheck.txt"))).contains("RESULT: PASS (1 of 1 re-rendered)");
	}

	@Test
	void aRunWithARefusedIncidentWritesNoSummary(@TempDir Path dir) throws IOException {
		Fixture f3 = fixture(F3);
		int line = f3.line("order-service", Section.LOGS, Change.NOT_COMPARED);
		ObjectNode incident = f3.incident(answer("order-service", "CONFIGURATION", line));
		((ObjectNode) incident.path("steps").get(0)).put("summary", "an older dossier");
		Path in = replayDir(dir.resolve("in"), "dev", incident);
		Path out = dir.resolve("out");

		LevelRecheck.Outcome outcome = this.recheck.run(in, ReplayDriver.DEFAULT_RECORDINGS, out);

		assertThat(outcome.passed()).isZero();
		assertThat(outcome.refused()).singleElement().asString().contains("dossier differs");
		assertThat(out.resolve("summary.tsv")).doesNotExist();
		assertThat(Files.readString(out.resolve("recheck.txt"))).contains("RESULT: EXCLUDED (1 of 1 refused)");
	}

	@Test
	void aRowOutsideTheDevSplitIsRefusedBeforeAnyRecheck(@TempDir Path dir) throws IOException {
		Fixture f3 = fixture(F3);
		int line = f3.line("order-service", Section.LOGS, Change.NOT_COMPARED);
		Path in = replayDir(dir.resolve("in"), "holdout", f3.incident(answer("order-service", "CONFIGURATION", line)));
		Path out = dir.resolve("out");

		assertThatThrownBy(() -> this.recheck.run(in, ReplayDriver.DEFAULT_RECORDINGS, out))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("split holdout")
			.hasMessageContaining("dev only");
		assertThat(out).doesNotExist();
	}

	/** A plain report naming {@code service|category} with one evidence item citing dossier line {@code line}. */
	private static ObjectNode answer(String service, String category, int line) {
		ObjectNode answer = JSON.createObjectNode();
		answer.put("summary", "The cause's own fact.");
		answer.putArray("evidence")
			.addObject()
			.put("toolCallId", "dossier")
			.put("tool", "dossier")
			.put("line", line)
			.put("why", "the cause's line");
		answer.put("rootCauseService", service);
		answer.put("rootCauseCategory", category);
		answer.putArray("alternatives");
		answer.put("confidence", 0.8);
		return answer;
	}

	private static Path replayDir(Path in, String split, ObjectNode incident) throws IOException {
		Files.createDirectories(in);
		Files.writeString(in.resolve("summary.tsv"), ReplayDriver.SUMMARY_HEADER + "\n" + row(split, "-") + "\n");
		Files.writeString(in.resolve("F3-decision-verify-agent-20261024T162555Z-r1-F3-1.json"),
				JSON.writeValueAsString(incident));
		return in;
	}

	/** A summary row of the F3 r1 sample in {@link ReplayDriver#SUMMARY_HEADER}'s columns. */
	private static String row(String split, String level) {
		return String.join("\t", "F3", "decision", "cloud", "1", "COMPLETED", "order-service|CONFIGURATION",
				"order-service|CONFIGURATION", "MATCH", "33", "12", "1.00", "0", "5000", "500", "5.0", split,
				"verify-agent-20261024T162555Z-r1-F3", "tables", "plain", "claude-haiku-5-5", "default", "4096", "60s",
				"", "", "0", "0", level);
	}

	private static Fixture fixture(String file) {
		JsonNode recording = JSON.readTree(ReplayDriver.DEFAULT_RECORDINGS.resolve(file).toFile());
		ReplayLabTools tools = new ReplayLabTools(JSON.readTree(ReplayDriver.DEFAULT_TOOLS_LIST.toFile()),
				List.of(recording));
		// Gathered at the recording's start, as a replay's clock starts there, a few milliseconds later.
		Instant gathered = Instant.parse(recording.path("startedAt").asString()).plusNanos(20_753_595);
		Dossier dossier = new DossierGatherer(tools, Clock.fixed(gathered, ZoneOffset.UTC), LAB, Policy.DEFAULT)
			.gather(recording.path("sinceMinutes").asInt(), new PolicyTally());
		return new Fixture(recording, DossierRenderer.indexed(dossier, LAB));
	}

	private record Fixture(JsonNode recording, DossierRenderer.Indexed dossier) {

		/** The first dossier line with this fact, numbered from 1. */
		int line(String owner, Section section, Change changed) {
			List<LineFact> facts = this.dossier.facts();
			return IntStream.range(0, facts.size())
				.filter(i -> facts.get(i).equals(new LineFact(owner, section, changed)))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no line " + owner + " " + section + " " + changed + " in "
						+ facts.stream().filter(fact -> fact.section() == section).toList() + "\n" + this.dossier.text()))
					+ 1;
		}

		/**
		 * A decision incident as an older run stored it: its {@code DOSSIER} step's message, its {@code REPORT} step's
		 * answer, and the report the loop stored for it, with no {@code verification}.
		 */
		ObjectNode incident(ObjectNode answer) {
			ReportCheck.Result checked = ReportCheck.check(answer, Map.of(), AgentMode.DECISION, this.dossier.text(),
					this.dossier.facts(), LAB);
			assertThat(checked.errors()).isEmpty();
			ObjectNode incident = JSON.createObjectNode();
			incident.put("status", "COMPLETED");
			incident.put("sinceMinutes", this.recording.path("sinceMinutes").asInt());
			ObjectNode report = incident.putObject("report");
			report.put("summary", checked.summary());
			ArrayNode evidence = report.putArray("evidence");
			checked.evidence().forEach(evidence::add);
			report.put("rootCauseService", answer.path("rootCauseService").asString());
			report.put("rootCauseCategory", answer.path("rootCauseCategory").asString());
			report.set("alternatives", answer.get("alternatives"));
			report.put("confidence", answer.path("confidence").asDouble());
			ArrayNode steps = incident.putArray("steps");
			steps.addObject()
				.put("stepNo", 1)
				.put("kind", "DOSSIER")
				.put("summary", Prompts.dossier(this.dossier.text()))
				.put("toolOutput", "[]");
			steps.addObject().put("stepNo", 2).put("kind", "REPORT").put("modelOutput", JSON.writeValueAsString(answer));
			return incident;
		}

	}

}
