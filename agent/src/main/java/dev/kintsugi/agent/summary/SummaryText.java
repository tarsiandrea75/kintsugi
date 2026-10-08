package dev.kintsugi.agent.summary;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** How summaries write values copied from a tool output. */
final class SummaryText {

	private static final JsonMapper JSON = JsonMapper.shared();

	private SummaryText() {
	}

	/**
	 * {@code text} as a JSON string literal, cut at its end to at most {@code max} characters (never inside a
	 * surrogate pair). A cut literal is followed by {@code (cut)}, outside the quotes. The escaping makes the literal a
	 * single, closed, labelled value whatever the text holds: quotes, newlines or words that look like instructions.
	 */
	static String quote(String text, int max) {
		boolean cut = text.length() > max;
		String kept = text;
		if (cut) {
			int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
			kept = text.substring(0, end);
		}
		return JSON.writeValueAsString(kept) + (cut ? "(cut)" : "");
	}

	/** A string field of {@code node} as a quoted literal, or {@code -} when it is absent or null. */
	static String field(JsonNode node, String name, int max) {
		JsonNode value = node.get(name);
		return value == null || value.isNull() ? "-" : quote(value.asString(), max);
	}

	/** A number field as the output wrote it, or {@code -} when it is absent or null. */
	static String number(JsonNode node, String name) {
		JsonNode value = node.get(name);
		return value == null || value.isNull() ? "-" : value.toString();
	}

	/** The time of an ISO-8601 field, or {@code null} when it is absent or unreadable. */
	static Instant time(JsonNode node, String name) {
		JsonNode value = node.get(name);
		if (value == null || !value.isString()) {
			return null;
		}
		try {
			return Instant.parse(value.asString());
		}
		catch (DateTimeParseException unreadable) {
			return null;
		}
	}

	/** Labels with their keys sorted, as {@code {key="value",...}}: the series' text for ties, and its display. */
	static String labels(JsonNode labels) {
		Map<String, String> sorted = new TreeMap<>();
		if (labels != null) {
			labels.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue().asString()));
		}
		return sorted.entrySet()
			.stream()
			.map(entry -> entry.getKey() + "=" + quote(entry.getValue(), 100))
			.collect(Collectors.joining(",", "{", "}"));
	}

}
