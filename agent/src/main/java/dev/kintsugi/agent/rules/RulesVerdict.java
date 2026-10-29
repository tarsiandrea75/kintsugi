package dev.kintsugi.agent.rules;

import java.util.List;
import java.util.Objects;

/**
 * Config 0's answer for one dossier (ADR-016): a service and a category in the agent's taxonomy, the rule that gave
 * them, and the dossier rows behind them, so every verdict can be checked by hand.
 *
 * @param service a lab service, or {@code UNKNOWN}
 * @param category a category of the report's taxonomy, never {@code RESOURCE_EXHAUSTION}
 * @param rule the rule that fired: {@link RulesDiagnosis#REACH}, {@link RulesDiagnosis#DOWN} or
 * {@link RulesDiagnosis#NONE}
 * @param facts short statements of the facts that fired the rule, at least one
 */
public record RulesVerdict(String service, String category, String rule, List<String> facts) {

	public RulesVerdict {
		Objects.requireNonNull(service, "service");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(rule, "rule");
		facts = List.copyOf(facts);
	}

}
