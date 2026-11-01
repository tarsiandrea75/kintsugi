package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.lab.ReplayLabTools;

/**
 * The replay driver's topology (odd/tasks/rcaeval-converter.md T5): the file named by {@code kintsugi.agent.topology},
 * as the agent takes it, the lab's by default. With RE2-OB's file, the RCAEval recording the converter wrote replays
 * with every gatherer call recorded; with the lab's, it does not, so the property is what makes it replay.
 */
class ReplayTopologyTest {

	private static final Path RCAEVAL = Path.of("src/test/resources/rcaeval");

	private static final String RE2_OB = "classpath:topology/re2-ob.json";

	private static final Topology RE2_OB_TOPOLOGY = Topology.load(new ClassPathResource("topology/re2-ob.json"));

	@Test
	void withoutThePropertyTheDriverReadsTheLabTopology() {
		assertThat(ReplayDriver.topology(name -> null)).isEqualTo(LAB);
	}

	@Test
	void thePropertyNamesTheTopologyFile() {
		assertThat(ReplayDriver.topology(Map.of(Topology.PROPERTY, RE2_OB)::get)).isEqualTo(RE2_OB_TOPOLOGY)
			.isNotEqualTo(LAB);
	}

	@Test
	void thePropertyTakesAFileUrl() {
		String file = Path.of("src/main/resources/topology/re2-ob.json").toAbsolutePath().toUri().toString();
		assertThat(ReplayDriver.topology(Map.of(Topology.PROPERTY, file)::get)).isEqualTo(RE2_OB_TOPOLOGY);
	}

	@Test
	void aMissingFileIsRefusedNamingIt() {
		assertThatThrownBy(
				() -> ReplayDriver.topology(Map.of(Topology.PROPERTY, "classpath:topology/no-such-file.json")::get))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("no-such-file.json");
	}

	@Test
	void theRcaevalRecordingReplaysOnItsTopologyWithEveryCallRecorded() {
		ReplayDriver.Sample sample = rulesSample(RE2_OB_TOPOLOGY);

		assertThat(sample.unrecorded()).isZero();
		assertThat(sample.dossierUnrecorded()).isZero();
		assertThat(sample.recorded()).isEqualTo(3 + 8 * 11);
		assertThat(sample.coverage()).isEqualTo(1.0);
		assertThat(sample.conflicts()).isZero();
		assertThat(sample.truth()).isEqualTo("checkoutservice|RESOURCE_EXHAUSTION");
		assertThat(sample.split()).isEqualTo("dev");
		assertThat(sample.summaryLine()).doesNotContain(ReplayLabTools.NOT_RECORDED);
	}

	@Test
	void onTheLabTopologyTheRcaevalRecordingIsNotRecorded() {
		assertThat(rulesSample(LAB).unrecorded()).isPositive();
	}

	/** The one dev case of the committed RCAEval recording, replayed on the {@code rules} route over {@code topology}. */
	private static ReplayDriver.Sample rulesSample(Topology topology) {
		ReplayDriver driver = ReplayDriver.rulesOnly(RCAEVAL, ReplayDriver.DEFAULT_TOOLS_LIST, DossierView.TABLES,
				DecisionReport.PLAIN, topology);
		List<ReplayDriver.Sample> samples = new ArrayList<>();
		driver.rulesSamples("dev", List.of("cpu"), 1, samples::add);
		assertThat(samples).hasSize(1);
		return samples.get(0);
	}

}
