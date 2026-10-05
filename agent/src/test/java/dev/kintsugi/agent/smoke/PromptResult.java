package dev.kintsugi.agent.smoke;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the model did for one prompt.
 *
 * @param prompt the prompt that was asked
 * @param calls the tool calls, in order
 * @param answer the final answer text
 * @param latency end-to-end time for the prompt
 */
record PromptResult(PromptCase prompt, List<ToolCall> calls, String answer, Duration latency) {

	/**
	 * Positive: at least one call and every call valid. Negative: no call at all. This is the
	 * gating rule of the adoption bar.
	 */
	boolean passed() {
		if (!prompt.positive()) {
			return calls.isEmpty();
		}
		return !calls.isEmpty() && calls.stream().noneMatch(ToolCall::error);
	}

	/** Whether the first call had valid arguments. Informational. */
	boolean firstCallValid() {
		return !calls.isEmpty() && !calls.get(0).error();
	}

	/** Empty when the prompt has no expected value; informational, never gating. */
	Optional<Boolean> containsExpected() {
		if (prompt.expected().isEmpty()) {
			return Optional.empty();
		}
		String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		return Optional.of(prompt.expected().stream().allMatch(e -> text.contains(e.toLowerCase(Locale.ROOT))));
	}

}
