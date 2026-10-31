package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The report's service enum derives from the topology (odd/tasks/topology-data.md T2, T4): the lab file's causes are
 * the enum {@code Causes.SERVICES} held before T4, which the schemas and checks now read from the topology.
 */
class CausesTopologyTest {

	@Test
	void theLabFileCausesAreTheReportServiceEnum() {
		assertThat(LAB.causes()).containsExactly("gateway-mock", "payment-service", "order-service",
				"inventory-service", "postgres", Causes.UNKNOWN);
	}

}
