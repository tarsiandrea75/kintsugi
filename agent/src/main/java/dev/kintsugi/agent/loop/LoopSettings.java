package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.kintsugi.agent.lab.Phase;

/**
 * The run's budgets, {@code kintsugi.agent.loop} (agent spec, "Budgets"). The tool budget and the phases' allowances
 * are fixed by the spec; the model-call budget, the incident's time budget and the warm-up's bound are settings, so
 * tests can make them small.
 *
 * @param maxModelCalls model calls per incident, 12; the warm-up is not one of them
 * @param incidentTimeout wall-clock time per incident from the end of its warm-up, tool calls included, 45 minutes
 * @param warmUpTimeout how long the warm-up request may take before the run ends {@code FAILED}, 300 s
 */
@ConfigurationProperties("kintsugi.agent.loop")
public record LoopSettings(int maxModelCalls, Duration incidentTimeout, Duration warmUpTimeout) {

	/** Tool calls per incident: the sum of the phases' allowances. */
	public static final int TOOL_BUDGET = 8;

	/**
	 * Calls per incident refused as a placeholder or a repeat without consuming any unit (T5c9); later ones consume a
	 * unit like a call that runs. Each uncounted refusal can add one model answer to a phase, so a valid run makes at
	 * most {@code TOOL_BUDGET + UNCOUNTED_REFUSALS} answers plus 2 report attempts, 12, within the model-call budget.
	 */
	public static final int UNCOUNTED_REFUSALS = 2;

	/** Tool calls per phase. */
	public static final Map<Phase, Integer> ALLOWANCES = Map.of(Phase.TRIAGE, 2, Phase.INVESTIGATE, 4,
			Phase.KNOWLEDGE, 2, Phase.CONCLUDE, 0);

}
