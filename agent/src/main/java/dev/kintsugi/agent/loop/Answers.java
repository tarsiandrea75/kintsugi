package dev.kintsugi.agent.loop;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads a model's plan or report answer as JSON. */
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
		try {
			return JSON.readTree(text);
		}
		catch (JacksonException unreadable) {
			return null;
		}
	}

}
