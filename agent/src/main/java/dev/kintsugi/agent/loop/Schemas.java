package dev.kintsugi.agent.loop;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.lab.Phase;

/**
 * The JSON schemas of the plan and the report, sent with their calls through {@code OllamaChatOptions.outputSchema}
 * (agent spec, "The plan", "The report"). Ollama constrains the answer with them, when the model honours it; code
 * checks every answer against the same rules anyway ({@link PlanCheck}, {@link ReportCheck}).
 */
final class Schemas {

	private static final JsonMapper JSON = JsonMapper.shared();

	static final String REPORT = JSON.writeValueAsString(report());

	private Schemas() {
	}

	/** The plan of {@code phase}: hypotheses with the report's enums, and next tools among the phase's own. */
	static String plan(Phase phase) {
		ObjectNode hypothesis = object(List.of("rootCauseService", "rootCauseCategory", "why"));
		hypothesis.withObject("properties").set("rootCauseService", enumOf(Causes.SERVICES));
		hypothesis.withObject("properties").set("rootCauseCategory", enumOf(Causes.CATEGORIES));
		hypothesis.withObject("properties").set("why", string(0, 200));
		ObjectNode plan = object(List.of("hypotheses", "nextTools"));
		plan.withObject("properties").set("hypotheses", array(hypothesis, 1, 3));
		plan.withObject("properties").set("nextTools", array(enumOf(phase.tools()), 0, 4));
		return JSON.writeValueAsString(plan);
	}

	private static ObjectNode report() {
		ObjectNode item = object(List.of("toolCallId", "tool", "quote", "why"));
		item.withObject("properties").set("toolCallId", JSON.createObjectNode().put("type", "integer").put("minimum", 1));
		item.withObject("properties").set("tool", JSON.createObjectNode().put("type", "string"));
		item.withObject("properties").set("quote", string(8, 300));
		item.withObject("properties").set("why", string(0, 200));
		ObjectNode report = object(
				List.of("rootCauseService", "rootCauseCategory", "summary", "confidence", "evidence"));
		ObjectNode properties = report.withObject("properties");
		properties.set("rootCauseService", enumOf(Causes.SERVICES));
		properties.set("rootCauseCategory", enumOf(Causes.CATEGORIES));
		properties.set("summary", string(1, 500));
		properties.set("confidence", JSON.createObjectNode().put("type", "number").put("minimum", 0).put("maximum", 1));
		properties.set("evidence", array(item, 0, 5));
		return report;
	}

	/** An object with exactly {@code fields}, all required. */
	private static ObjectNode object(List<String> fields) {
		ObjectNode object = JSON.createObjectNode().put("type", "object");
		object.putObject("properties");
		ArrayNode required = object.putArray("required");
		fields.forEach(required::add);
		object.put("additionalProperties", false);
		return object;
	}

	private static ObjectNode enumOf(List<String> values) {
		ObjectNode node = JSON.createObjectNode().put("type", "string");
		ArrayNode allowed = node.putArray("enum");
		values.forEach(allowed::add);
		return node;
	}

	private static ObjectNode string(int min, int max) {
		return JSON.createObjectNode().put("type", "string").put("minLength", min).put("maxLength", max);
	}

	private static ObjectNode array(ObjectNode items, int min, int max) {
		ObjectNode array = JSON.createObjectNode().put("type", "array").put("minItems", min).put("maxItems", max);
		array.set("items", items);
		return array;
	}

}
