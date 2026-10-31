package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.Gathered;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.lab.ReplayLabTools;
import dev.kintsugi.agent.lab.ToolResult;
import dev.kintsugi.agent.loop.ReplayCases.Recording;
import dev.kintsugi.agent.rules.RulesDiagnosis;
import dev.kintsugi.agent.rules.RulesVerdict;

/**
 * AC1 of odd/tasks/rcaeval-converter.md: a recording the RCAEval converter wrote replays through the unchanged
 * gatherer. The recording under {@code rcaeval/} is the converter's output on the synthetic fixture
 * ({@code evals/tests/fixtures/rcaeval/}, no real data); {@code test_rcaeval_convert.py} regenerates it and fails on
 * any byte of drift.
 */
class RcaevalRecordingTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final Path RECORDINGS = Path.of("src/test/resources/rcaeval");

	@Test
	@SuppressWarnings("unchecked")
	void everyGathererCallIsRecordedAndTheRulesRunOnTheDossier() throws Exception {
		List<Recording> rows = ReplayCases.read(RECORDINGS).decisionCases("dev", List.of("cpu"));
		assertThat(rows).hasSize(1);
		JsonNode incident = JSON.readTree(RECORDINGS.resolve(rows.get(0).file()).toFile());
		assertThat(incident.path("symptom").asString()).isNotBlank();
		Topology topology = Topology.load(new ClassPathResource("topology/re2-ob.json"));
		ReplayLabTools tools = new ReplayLabTools(JSON.createObjectNode(), List.of(incident));
		Clock clock = Clock.fixed(Instant.parse(incident.path("startedAt").asString()), ZoneOffset.UTC);

		Dossier dossier = new DossierGatherer(tools, clock, topology).gather(incident.path("sinceMinutes").asInt());

		assertThat(dossier.calls()).extracting(ToolResult::rawOutput)
			.noneMatch(output -> output.startsWith(ReplayLabTools.NOT_RECORDED));
		assertThat(tools.unrecordedCalls()).isZero();
		assertThat(tools.recordedCalls()).isEqualTo(dossier.calls().size()).isEqualTo(3 + 5 * 11);
		assertThat(tools.conflicts()).isZero();
		// T4: the fixture's traces give incident traffic whose client rows sit on the topology's edges.
		assertThat(dossier.traffic().incident()).isInstanceOf(Gathered.Ok.class);
		List<TrafficRow> traffic = ((Gathered.Ok<List<TrafficRow>>) dossier.traffic().incident()).value();
		assertThat(traffic).isNotEmpty()
			.anyMatch(row -> topology.edges().stream().anyMatch(edge -> edge.callerRow(row)));
		RulesVerdict verdict = RulesDiagnosis.diagnose(dossier, topology);
		assertThat(verdict).isNotNull();
		// A named service, or UNKNOWN when no rule fires: either way the rules read the converted dossier.
		assertThat(verdict.service()).isIn(java.util.stream.Stream
			.concat(topology.services().stream(), java.util.stream.Stream.of("UNKNOWN"))
			.toList());
	}

}
