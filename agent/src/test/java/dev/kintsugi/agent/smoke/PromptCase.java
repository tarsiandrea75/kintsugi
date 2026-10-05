package dev.kintsugi.agent.smoke;

import java.util.List;

/**
 * One smoke-test prompt.
 *
 * @param id stable identifier used in the report, e.g. {@code P1}
 * @param prompt the user message, in English
 * @param positive whether the model is expected to call a tool
 * @param expected values the answer should contain (informational, case-insensitive); empty when
 * any answer is acceptable
 */
record PromptCase(String id, String prompt, boolean positive, List<String> expected) {

	static PromptCase positive(String id, String prompt, String... expected) {
		return new PromptCase(id, prompt, true, List.of(expected));
	}

	static PromptCase negative(String id, String prompt, String... expected) {
		return new PromptCase(id, prompt, false, List.of(expected));
	}

	@Override
	public String toString() {
		return id + ": " + prompt;
	}

}
