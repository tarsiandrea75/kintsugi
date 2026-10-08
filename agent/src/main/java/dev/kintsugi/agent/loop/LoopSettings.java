package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.kintsugi.agent.lab.Phase;

/**
 * The run's budgets, {@code kintsugi.agent.loop} (agent spec, "Budgets"). The tool budget and the phases' allowances
 * are fixed by the spec; the model-call budget and the incident's time budget are settings, so tests can make them
 * small.
 *
 * @param maxModelCalls model calls per incident, 16
 * @param incidentTimeout wall-clock time per incident from its {@code started_at}, tool calls included, 10 minutes
 */
@ConfigurationProperties("kintsugi.agent.loop")
public record LoopSettings(int maxModelCalls, Duration incidentTimeout) {

	/** Tool calls per incident: the sum of the phases' allowances. */
	public static final int TOOL_BUDGET = 8;

	/** Tool calls per phase. */
	public static final Map<Phase, Integer> ALLOWANCES = Map.of(Phase.TRIAGE, 2, Phase.INVESTIGATE, 4,
			Phase.KNOWLEDGE, 2, Phase.CONCLUDE, 0);

}
