package dev.kintsugi.agent.loop;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON schema of the report, sent with its call through {@code OllamaChatOptions.outputSchema} (agent spec, "The
 * report"). Ollama constrains the answer with it, when the model honours it; code checks every answer against the same
 * rules anyway ({@link ReportCheck}). The plan has no schema sent: it travels in the text of a call that offers tools,
 * and Ollama cannot constrain an answer by a schema and offer tools in the same call ({@link PlanCheck} checks it).
 */
final class Schemas {

	private static final JsonMapper JSON = JsonMapper.shared();

	static final String REPORT = JSON.writeValueAsString(report());

	private Schemas() {
	}

	private static ObjectNode report() {
		ObjectNode item = object(List.of("toolCallId", "tool", "quote", "why"));
		item.withObject("properties").set("toolCallId", JSON.createObjectNode().put("type", "integer").put("minimum", 1));
		item.withObject("properties").set("tool", JSON.createObjectNode().put("type", "string"));
		item.withObject("properties").set("quote", string(ReportCheck.MIN_QUOTE_LENGTH, ReportCheck.MAX_QUOTE_LENGTH));
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
