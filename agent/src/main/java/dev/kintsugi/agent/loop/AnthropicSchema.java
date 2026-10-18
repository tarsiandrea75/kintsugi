package dev.kintsugi.agent.loop;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The report schema as the cloud route sends it (agent spec, "Routes"): Anthropic's structured outputs reject a schema
 * with numerical constraints, string length constraints, {@code maxItems} or a {@code minItems} above 1, and require
 * {@code additionalProperties: false} on every object. In the first live cloud run every report call failed with HTTP
 * 400 ({@code For 'number' type, properties maximum, minimum are not supported}). So those keywords are dropped,
 * everywhere in the schema, and {@link ReportCheck} enforces the constraints they carried, on both routes, as it always
 * did: the model proposes, code verifies. The local route sends the schema unchanged.
 */
final class AnthropicSchema {

	private static final JsonMapper JSON = JsonMapper.shared();

	/** The keywords Anthropic's structured outputs do not support, whatever their value. */
	private static final Set<String> UNSUPPORTED = Set.of("minimum", "maximum", "exclusiveMinimum",
			"exclusiveMaximum", "multipleOf", "minLength", "maxLength", "maxItems");

	/** The keywords whose value maps names to schemas: their keys are names, not keywords. */
	private static final Set<String> NAMED_SCHEMAS = Set.of("properties", "$defs", "definitions");

	private AnthropicSchema() {
	}

	/** {@code schema} without what Anthropic rejects, every object closed. */
	static String of(String schema) {
		JsonNode tree = JSON.readTree(schema);
		clean(tree);
		return JSON.writeValueAsString(tree);
	}

	private static void clean(JsonNode node) {
		if (node.isArray()) {
			node.forEach(AnthropicSchema::clean);
			return;
		}
		if (!(node instanceof ObjectNode schema)) {
			return;
		}
		schema.remove(UNSUPPORTED);
		JsonNode minItems = schema.get("minItems");
		if (minItems != null && (!minItems.isIntegralNumber() || minItems.intValue() > 1)) {
			schema.remove("minItems");
		}
		if ("object".equals(schema.path("type").asString("")) || schema.has("properties")) {
			schema.put("additionalProperties", false);
		}
		for (Map.Entry<String, JsonNode> entry : schema.properties()) {
			if (NAMED_SCHEMAS.contains(entry.getKey())) {
				entry.getValue().forEach(AnthropicSchema::clean);
			}
			else {
				clean(entry.getValue());
			}
		}
	}

}
