package dev.kintsugi.agent.smoke;

import java.util.List;

/**
 * One smoke-test prompt.
 *
 * @param id stable identifier used in the report, e.g. {@code P1}
 * @param prompt the user message, in English
 * @param positive whether the model is expected to call a tool
 * @param minCalls the minimum number of tool calls a valid answer needs; 1 for positives by
 * default, 0 for negatives
 * @param expected values the answer should contain (informational, case-insensitive); empty when
 * any answer is acceptable
 */
record PromptCase(String id, String prompt, boolean positive, int minCalls, List<String> expected) {

	static PromptCase positive(String id, String prompt, String... expected) {
		return new PromptCase(id, prompt, true, 1, List.of(expected));
	}

	static PromptCase negative(String id, String prompt, String... expected) {
		return new PromptCase(id, prompt, false, 0, List.of(expected));
	}

	/** A copy that needs at least {@code minCalls} tool calls, e.g. 2 for a two-zone prompt. */
	PromptCase withMinCalls(int minCalls) {
		return new PromptCase(id, prompt, positive, minCalls, expected);
	}

	@Override
	public String toString() {
		return id + ": " + prompt;
	}

}
