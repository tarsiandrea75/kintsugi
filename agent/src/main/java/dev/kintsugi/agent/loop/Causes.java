package dev.kintsugi.agent.loop;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The report's taxonomy, chosen by the human (ADR-010, "Plan and report schemas"): generic SRE vocabularies that a
 * script compares with the truth by equality. The service vocabulary is the system's, so it comes from the loaded
 * topology ({@link dev.kintsugi.agent.dossier.Topology#causes()}, odd/tasks/topology-data.md T4); the categories are
 * the report's own, the same for every system.
 */
final class Causes {

	static final String UNKNOWN = "UNKNOWN";

	/**
	 * The catalog's four categories, then NETWORK and CODE_DEFECT, decoys that no truth holds, so the taxonomy is wider
	 * than the catalog (ADR-029), then UNKNOWN.
	 */
	static final List<String> CATEGORIES = List.of("DEPENDENCY_LATENCY", "DEPENDENCY_REJECTING", "CONFIGURATION",
			"RESOURCE_EXHAUSTION", "NETWORK", "CODE_DEFECT", UNKNOWN);

	private Causes() {
	}

	/** The error for a field that must hold one of {@code allowed}, or null when it does. */
	static String enumError(String field, JsonNode value, List<String> allowed) {
		if (value != null && value.isString() && allowed.contains(value.asString())) {
			return null;
		}
		return field + " must be one of " + String.join(", ", allowed) + "; got " + shown(value);
	}

	/** A value as an error shows it: its JSON text, cut at 80 characters, or {@code nothing} when absent. */
	static String shown(JsonNode value) {
		if (value == null || value.isMissingNode()) {
			return "nothing";
		}
		String text = value.toString();
		return text.length() > 80 ? text.substring(0, 80) + "..." : text;
	}

	/** Whether {@code value} is a string of {@code min} to {@code max} characters (code points). */
	static boolean string(JsonNode value, int min, int max) {
		if (value == null || !value.isString()) {
			return false;
		}
		String text = value.asString();
		int length = text.codePointCount(0, text.length());
		return length >= min && length <= max;
	}

}
