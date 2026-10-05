package dev.kintsugi.agent.smoke;

import java.util.ArrayList;
import java.util.List;

/** Collects the tool calls of one prompt. Cleared between prompts; synchronized for safety. */
final class ToolCallRecorder {

	private final List<ToolCall> calls = new ArrayList<>();

	synchronized void record(ToolCall call) {
		calls.add(call);
	}

	/** A snapshot, so a later {@link #clear()} does not change it. */
	synchronized List<ToolCall> calls() {
		return List.copyOf(calls);
	}

	synchronized void clear() {
		calls.clear();
	}

}
