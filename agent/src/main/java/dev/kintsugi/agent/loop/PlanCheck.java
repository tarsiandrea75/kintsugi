package dev.kintsugi.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.lab.Phase;

/** Checks a plan against its schema in code, whatever the model returned (agent spec, "The plan"). */
final class PlanCheck {

	private static final Set<String> FIELDS = Set.of("hypotheses", "nextTools");

	private static final Set<String> HYPOTHESIS_FIELDS = Set.of("rootCauseService", "rootCauseCategory", "why");

	/**
	 * The most characters of a hypothesis's {@code why} (T5c10; 200 before): plans of the sixth live run were rejected
	 * for a why just over 200, often because it starts with the toolCallId it cites.
	 */
	static final int MAX_WHY_LENGTH = 300;

	private PlanCheck() {
	}

	/** Every rule {@code plan} breaks, each naming its field; empty when the plan is valid in {@code phase}. */
	static List<String> errors(JsonNode plan, Phase phase, Topology topology) {
		return check(plan, phase, topology).errors();
	}

	/**
	 * Checks {@code plan} in {@code phase}. A {@code nextTools} entry that names a tool outside the phase is dropped and
	 * recorded, not an error (T5c12): the sixth to eighth live runs lost every KNOWLEDGE plan to a {@code search_logs}
	 * entry. Every other rule still invalidates the plan. A hypothesis's {@code rootCauseService} is one of
	 * {@code topology}'s causes ({@link Topology#causes()}).
	 */
	static Result check(JsonNode plan, Phase phase, Topology topology) {
		if (plan == null || !plan.isObject()) {
			return new Result(List.of("the answer is not a JSON object"), List.of(), plan);
		}
		List<String> errors = new ArrayList<>();
		List<String> dropped = new ArrayList<>();
		List<Integer> droppedAt = new ArrayList<>();
		unexpected(plan, FIELDS, "", errors);
		JsonNode hypotheses = plan.get("hypotheses");
		if (hypotheses == null || !hypotheses.isArray() || hypotheses.size() < 1 || hypotheses.size() > 3) {
			errors.add("hypotheses must be an array of 1 to 3 items");
		}
		else {
			for (int i = 0; i < hypotheses.size(); i++) {
				hypothesis(hypotheses.get(i), "hypotheses[" + i + "]", topology.causes(), errors);
			}
		}
		JsonNode nextTools = plan.get("nextTools");
		if (nextTools == null || !nextTools.isArray() || nextTools.size() > 4) {
			errors.add("nextTools must be an array of 0 to 4 tool names");
		}
		else {
			for (int i = 0; i < nextTools.size(); i++) {
				JsonNode tool = nextTools.get(i);
				if (!tool.isString()) {
					errors.add("nextTools[" + i + "] must be one of this phase's tools: "
							+ String.join(", ", phase.tools()) + "; got " + Causes.shown(tool));
				}
				else if (!phase.tools().contains(tool.asString())) {
					droppedAt.add(i);
					dropped.add("nextTools[" + i + "] " + Causes.shown(tool) + " is not a tool of " + phase
							+ ", which offers " + String.join(", ", phase.tools()));
				}
			}
		}
		if (!errors.isEmpty() || droppedAt.isEmpty()) {
			return new Result(errors, dropped, plan);
		}
		ObjectNode kept = ((ObjectNode) plan).deepCopy();
		ArrayNode tools = (ArrayNode) kept.get("nextTools");
		droppedAt.reversed().forEach(tools::remove);
		return new Result(errors, dropped, kept);
	}

	/**
	 * What the check found.
	 * @param errors every rule the plan breaks; empty when it is valid
	 * @param dropped the {@code nextTools} entries left out because the phase does not offer them, each with why
	 * @param plan the plan without the dropped entries, the one to record and show; the input when nothing was dropped
	 */
	record Result(List<String> errors, List<String> dropped, JsonNode plan) {
	}

	private static void hypothesis(JsonNode hypothesis, String name, List<String> causes, List<String> errors) {
		if (!hypothesis.isObject()) {
			errors.add(name + " must be an object {rootCauseService, rootCauseCategory, why}");
			return;
		}
		unexpected(hypothesis, HYPOTHESIS_FIELDS, name + ".", errors);
		addIfError(errors, Causes.enumError(name + ".rootCauseService", hypothesis.get("rootCauseService"),
				causes));
		addIfError(errors, Causes.enumError(name + ".rootCauseCategory", hypothesis.get("rootCauseCategory"),
				Causes.CATEGORIES));
		if (!Causes.string(hypothesis.get("why"), 0, MAX_WHY_LENGTH)) {
			errors.add(name + ".why must be a string of at most " + MAX_WHY_LENGTH + " characters");
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
