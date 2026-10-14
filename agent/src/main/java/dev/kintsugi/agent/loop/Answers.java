package dev.kintsugi.agent.loop;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads a model's report answer, or the plan inside an answer's text, as JSON. */
final class Answers {

	private static final JsonMapper JSON = JsonMapper.shared();

	private Answers() {
	}

	/**
	 * The answer as a JSON tree, or null when it is not JSON. A model that ignores the schema constraint may wrap its
	 * JSON in a Markdown code fence; the fence is dropped, nothing else is repaired.
	 */
	static JsonNode json(String answer) {
		if (answer == null) {
			return null;
		}
		String text = answer.strip();
		if (text.startsWith("```") && text.endsWith("```") && text.length() >= 6) {
			text = text.substring(3, text.length() - 3);
			int newline = text.indexOf('\n');
			text = newline < 0 ? text : text.substring(newline + 1);
		}
		return parse(text);
	}

	/**
	 * The JSON object written in an answer's text, or null when there is none. The text may hold prose around it, as
	 * an answer that also requests tool calls does: the object is the whole text, else the first Markdown code fence
	 * that holds one, else the text from the first {@code {} to the last {@code }}. Nothing else is repaired.
	 */
	static JsonNode embedded(String answer) {
		JsonNode whole = json(answer);
		if (whole != null || answer == null) {
			return whole != null && whole.isObject() ? whole : null;
		}
		int fence = answer.indexOf("```");
		while (fence >= 0) {
			int end = answer.indexOf("```", fence + 3);
			if (end < 0) {
				break;
			}
			JsonNode fenced = json(answer.substring(fence, end + 3));
			if (fenced != null && fenced.isObject()) {
				return fenced;
			}
			fence = answer.indexOf("```", end + 3);
		}
		int open = answer.indexOf('{');
		int close = answer.lastIndexOf('}');
		if (open < 0 || close < open) {
			return null;
		}
		JsonNode braced = parse(answer.substring(open, close + 1));
		return braced != null && braced.isObject() ? braced : null;
	}

	private static JsonNode parse(String text) {
		try {
			return JSON.readTree(text);
		}
		catch (JacksonException unreadable) {
			return null;
		}
	}

}
