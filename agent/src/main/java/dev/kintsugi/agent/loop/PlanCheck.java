package dev.kintsugi.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

import dev.kintsugi.agent.lab.Phase;

/** Checks a plan against its schema in code, whatever the model returned (agent spec, "The plan"). */
final class PlanCheck {

	private static final Set<String> FIELDS = Set.of("hypotheses", "nextTools");

	private static final Set<String> HYPOTHESIS_FIELDS = Set.of("rootCauseService", "rootCauseCategory", "why");

	private PlanCheck() {
	}

	/** Every rule {@code plan} breaks, each naming its field; empty when the plan is valid in {@code phase}. */
	static List<String> errors(JsonNode plan, Phase phase) {
		if (plan == null || !plan.isObject()) {
			return List.of("the answer is not a JSON object");
		}
		List<String> errors = new ArrayList<>();
		unexpected(plan, FIELDS, "", errors);
		JsonNode hypotheses = plan.get("hypotheses");
		if (hypotheses == null || !hypotheses.isArray() || hypotheses.size() < 1 || hypotheses.size() > 3) {
			errors.add("hypotheses must be an array of 1 to 3 items");
		}
		else {
			for (int i = 0; i < hypotheses.size(); i++) {
				hypothesis(hypotheses.get(i), "hypotheses[" + i + "]", errors);
			}
		}
		JsonNode nextTools = plan.get("nextTools");
		if (nextTools == null || !nextTools.isArray() || nextTools.size() > 4) {
			errors.add("nextTools must be an array of 0 to 4 tool names");
		}
		else {
			for (int i = 0; i < nextTools.size(); i++) {
				JsonNode tool = nextTools.get(i);
				if (!tool.isString() || !phase.tools().contains(tool.asString())) {
					errors.add("nextTools[" + i + "] must be one of this phase's tools: "
							+ String.join(", ", phase.tools()) + "; got " + Causes.shown(tool));
				}
			}
		}
		return errors;
	}

	private static void hypothesis(JsonNode hypothesis, String name, List<String> errors) {
		if (!hypothesis.isObject()) {
			errors.add(name + " must be an object {rootCauseService, rootCauseCategory, why}");
			return;
		}
		unexpected(hypothesis, HYPOTHESIS_FIELDS, name + ".", errors);
		addIfError(errors, Causes.enumError(name + ".rootCauseService", hypothesis.get("rootCauseService"),
				Causes.SERVICES));
		addIfError(errors, Causes.enumError(name + ".rootCauseCategory", hypothesis.get("rootCauseCategory"),
				Causes.CATEGORIES));
		if (!Causes.string(hypothesis.get("why"), 0, 200)) {
			errors.add(name + ".why must be a string of at most 200 characters");
		}
	}

	/** Adds an error for each field of {@code node} outside {@code allowed}. */
	static void unexpected(JsonNode node, Set<String> allowed, String prefix, List<String> errors) {
		String fields = String.join(", ", allowed.stream().sorted().toList());
		node.properties()
			.stream()
			.map(Map.Entry::getKey)
			.filter(field -> !allowed.contains(field))
			.sorted()
			.forEach(field -> errors.add(prefix + "unexpected field " + Causes.shown(StringNode.valueOf(field))
					+ "; allowed: " + fields));
	}

	static void addIfError(List<String> errors, String error) {
		if (error != null) {
			errors.add(error);
		}
	}

}
