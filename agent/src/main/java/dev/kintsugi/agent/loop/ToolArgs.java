package dev.kintsugi.agent.loop;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checks code makes on a tool call's arguments before it runs (T5c9; agent spec, "Phases and their tools"). A
 * placeholder such as {@code "<SELECT_A_PENDING_ORDER_ID>"} is not a value, and a call identical to an earlier one
 * would only return what the model already has: neither is worth one of the incident's tool calls.
 */
final class ToolArgs {

	/** A whole string value that is a name in angle brackets: word characters and spaces, starting with a word one. */
	private static final Pattern PLACEHOLDER = Pattern.compile("<\\w[\\w ]*>");

	private static final JsonMapper JSON = JsonMapper.shared();

	private ToolArgs() {
	}

	/**
	 * The path of the first argument whose string value is a placeholder ({@code orderId}, {@code filters.ids[1]}), or
	 * null when there is none or the arguments are not JSON.
	 */
	static String placeholder(String arguments) {
		JsonNode parsed = parsed(arguments);
		return parsed == null ? null : placeholder(parsed, "");
	}

	private static String placeholder(JsonNode node, String path) {
		if (node.isString()) {
			return PLACEHOLDER.matcher(node.asString().strip()).matches() ? path : null;
		}
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> field : node.properties()) {
				String found = placeholder(field.getValue(), path.isEmpty() ? field.getKey() : path + "." + field.getKey());
				if (found != null) {
					return found;
				}
			}
		}
		else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				String found = placeholder(node.get(i), path + "[" + i + "]");
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * Whether two calls' arguments are the same: equal as JSON, whatever the key order or spacing (an object's fields
	 * compare as a map, an array's items in order); blank arguments are the empty object; arguments that are not JSON
	 * compare as written.
	 */
	static boolean same(String a, String b) {
		JsonNode left = parsed(a);
		JsonNode right = parsed(b);
		if (left == null || right == null) {
			return left == null && right == null && Objects.equals(a, b);
		}
		return left.equals(right);
	}

	private static JsonNode parsed(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return JSON.createObjectNode();
		}
		try {
			return JSON.readTree(arguments);
		}
		catch (JacksonException notJson) {
			return null;
		}
	}

}
