package dev.kintsugi.agent.incident;

import tools.jackson.databind.JsonNode;

/**
 * The validated body of {@code POST /incidents}, stored as {@code incidents.signal} (agent spec, "Open an incident").
 *
 * @param symptom the symptom, trimmed: 10 to 500 characters, no control characters
 * @param sinceMinutes the default window offered to the model, 1 to 360 minutes
 */
record IncidentSignal(String symptom, int sinceMinutes) {

	static final int MIN_SYMPTOM = 10;

	static final int MAX_SYMPTOM = 500;

	static final int MIN_WINDOW = 1;

	static final int MAX_WINDOW = 360;

	static final String BODY_RULE = "the body must be a JSON object {\"symptom\": <string>, \"sinceMinutes\": <integer>}";

	static final String SYMPTOM_RULE = "symptom must be a string of " + MIN_SYMPTOM + " to " + MAX_SYMPTOM
			+ " characters after trimming, with no control characters";

	static final String WINDOW_RULE = "sinceMinutes must be an integer from " + MIN_WINDOW + " to " + MAX_WINDOW;

	/** Validates {@code body}, the symptom first; {@code null} stands for a missing or unparsable body. */
	static IncidentSignal from(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new InvalidIncidentException(null, BODY_RULE);
		}
		JsonNode symptomNode = body.get("symptom");
		if (symptomNode == null || !symptomNode.isString()) {
			throw new InvalidIncidentException("symptom", SYMPTOM_RULE);
		}
		String symptom = symptomNode.asString().strip();
		int length = symptom.codePointCount(0, symptom.length());
		if (length < MIN_SYMPTOM || length > MAX_SYMPTOM
				|| symptom.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL)) {
			throw new InvalidIncidentException("symptom", SYMPTOM_RULE);
		}
		// isInt() is true only for a JSON integer that fits an int: a decimal (15.5, 15.0), a string or a huge number
		// is refused, never coerced.
		JsonNode window = body.get("sinceMinutes");
		if (window == null || !window.isInt() || window.intValue() < MIN_WINDOW || window.intValue() > MAX_WINDOW) {
			throw new InvalidIncidentException("sinceMinutes", WINDOW_RULE);
		}
		return new IncidentSignal(symptom, window.intValue());
	}

}
