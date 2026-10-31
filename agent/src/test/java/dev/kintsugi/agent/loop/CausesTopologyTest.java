package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import dev.kintsugi.agent.dossier.Topology;

/**
 * The report's service enum derives from the topology (odd/tasks/topology-data.md T2): the lab file's causes equal
 * today's {@link Causes#SERVICES}. It lives in this package because that constant is package-private.
 */
class CausesTopologyTest {

	@Test
	void theLabFileCausesAreTheReportServiceEnum() {
		assertThat(Topology.load(new ClassPathResource("topology/lab.json")).causes()).isEqualTo(Causes.SERVICES);
	}

}
