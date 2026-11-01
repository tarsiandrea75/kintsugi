package dev.kintsugi.agent.rules;

import java.util.List;
import java.util.Objects;

/**
 * One of Config H's proposals for one dossier (ADR-019): a service and a category in the agent's taxonomy, the rule
 * step that proposed them, and the dossier rows behind them, so the model picks among checked facts and every
 * candidate can be checked by hand.
 *
 * @param service a lab service, never {@code UNKNOWN}
 * @param category a category of the report's taxonomy, never {@code UNKNOWN}; {@code RESOURCE_EXHAUSTION} only from
 * Config 0R's R-RES (ADR-021)
 * @param rule the step that proposed it: {@link RulesDiagnosis#REACH}, {@link RulesDiagnosis#DOWN}, or {@code R-RES}
 * under {@link RulesVersion#V2}
 * @param facts short statements of the facts behind the proposal, at least one
 */
public record RulesCandidate(String service, String category, String rule, List<String> facts) {

	public RulesCandidate {
		Objects.requireNonNull(service, "service");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(rule, "rule");
		facts = List.copyOf(facts);
	}

}
