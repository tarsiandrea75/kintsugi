package dev.kintsugi.agent.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.lab.Phase;

/**
 * The policy gate's risk table and verdicts (agent spec, "The policy authorizes every tool call"; ADR-027): every
 * current tool reads, any other name writes, and only a read is authorized.
 */
class PolicyTest {

	/** The ten mcp-tools tools: every phase's and the dossier's. */
	private static List<String> currentTools() {
		List<String> tools = new ArrayList<>(Phase.DOSSIER_TOOLS);
		for (Phase phase : Phase.values()) {
			tools.addAll(phase.tools());
		}
		return tools;
	}

	@Test
	void everyCurrentToolIsClassifiedReadAndAuthorized() {
		assertThat(currentTools()).hasSize(10);
		for (String tool : currentTools()) {
			assertThat(Policy.DEFAULT.risk(tool)).as(tool).isEqualTo(Risk.READ);
			assertThat(Policy.DEFAULT.authorizes(tool)).as(tool).isTrue();
		}
	}

	@Test
	void theTableClassifiesExactlyTheCurrentTools() {
		assertThat(Policy.RISKS.keySet()).containsExactlyInAnyOrderElementsOf(currentTools());
		assertThat(Policy.RISKS.values()).containsOnly(Risk.READ);
	}

	@Test
	void aToolNotInTheTableIsWriteAndRefused() {
		for (String tool : List.of("restart_service", "SEARCH_LOGS", "search_logs ", "")) {
			assertThat(Policy.DEFAULT.risk(tool)).as(tool).isEqualTo(Risk.WRITE);
			assertThat(Policy.DEFAULT.authorizes(tool)).as(tool).isFalse();
		}
		assertThat(Policy.DEFAULT.risk(null)).isEqualTo(Risk.WRITE);
		assertThat(Policy.DEFAULT.authorizes(null)).isFalse();
	}

	@Test
	void aPolicyOverAnotherTableFailsClosedOnEveryToolItLacks() {
		Policy onlyLogs = new Policy(Map.of("search_logs", Risk.READ, "restart_service", Risk.WRITE));

		assertThat(onlyLogs.authorizes("search_logs")).isTrue();
		assertThat(onlyLogs.authorizes("restart_service")).isFalse();
		assertThat(onlyLogs.authorizes("status_summary")).isFalse();
	}

	@Test
	void theCountsCountTheExecutedWritesAndTheRefusals() {
		Policy onlyLogs = new Policy(Map.of("search_logs", Risk.READ));

		assertThat(onlyLogs.counts(List.of("search_logs", "search_logs"), 2)).isEqualTo(new PolicyCounts(0, 2));
		// A write that ran would be counted: the gate makes it impossible, the count is how a run shows it.
		assertThat(onlyLogs.counts(List.of("search_logs", "restart_service"), 0)).isEqualTo(new PolicyCounts(1, 0));
		assertThat(Policy.DEFAULT.counts(List.of(), 0)).isEqualTo(PolicyCounts.NONE);
	}

}
