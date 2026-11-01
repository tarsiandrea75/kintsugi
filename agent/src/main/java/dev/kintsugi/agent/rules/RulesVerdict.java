package dev.kintsugi.agent.rules;

import java.util.List;
import java.util.Objects;

/**
 * A rules configuration's answer for one dossier (Config 0, ADR-016; Config 0R, ADR-021): a service and a category
 * in the agent's taxonomy, the rule that gave them, and the dossier rows behind them, so every verdict can be checked
 * by hand.
 *
 * @param service a lab service, or {@code UNKNOWN}
 * @param category a category of the report's taxonomy; {@code RESOURCE_EXHAUSTION} only from Config 0R's R-RES
 * (ADR-021), never from Config 0
 * @param rule the rule that fired: {@link RulesDiagnosis#REACH}, {@link RulesDiagnosis#DOWN},
 * {@link RulesDiagnosis#NONE}, or {@code R-RES} under {@link RulesVersion#V2}
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
