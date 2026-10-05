package dev.kintsugi.agent.smoke;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates a real tool callback and records every call the model makes through it. The smoke test
 * scores these records, not only the final text: a right answer with a wrong call still counts as
 * wrong.
 */
final class RecordingToolCallback implements ToolCallback {

	private static final String ERROR_PREFIX = "Error:";

	private final ToolCallback delegate;

	private final ToolCallRecorder recorder;

	RecordingToolCallback(ToolCallback delegate, ToolCallRecorder recorder) {
		this.delegate = delegate;
		this.recorder = recorder;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return record(toolInput, () -> delegate.call(toolInput));
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		return record(toolInput, () -> delegate.call(toolInput, toolContext));
	}

	/**
	 * The tool returns {@code Error: ...} on bad input. Method tool callbacks serialize a String
	 * result as JSON, so the wire value is usually quoted: {@code "Error: ..."}.
	 */
	static boolean isError(String result) {
		if (result == null) {
			return false;
		}
		String text = result.strip();
		if (text.startsWith("\"")) {
			text = text.substring(1);
		}
		return text.startsWith(ERROR_PREFIX);
	}

	private String record(String toolInput, Supplier<String> invocation) {
		long start = System.nanoTime();
		boolean error = true;
		try {
			String result = invocation.get();
			error = isError(result);
			return result;
		}
		finally {
			Duration latency = Duration.ofNanos(System.nanoTime() - start);
			recorder.record(new ToolCall(getToolDefinition().name(), toolInput, error, latency));
		}
	}

}
