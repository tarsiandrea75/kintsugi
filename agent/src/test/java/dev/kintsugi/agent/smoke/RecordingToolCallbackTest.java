package dev.kintsugi.agent.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import dev.kintsugi.agent.tools.TimeTools;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class RecordingToolCallbackTest {

	private final ToolCallback timeTool = ToolCallbacks
		.from(new TimeTools(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC)))[0];

	private final ToolCallRecorder recorder = new ToolCallRecorder();

	private final RecordingToolCallback recording = new RecordingToolCallback(timeTool, recorder);

	@Test
	void delegatesAndRecordsASuccessfulCall() {
		String args = "{\"zone\":\"Asia/Tokyo\"}";

		String result = recording.call(args);

		assertThat(result).isEqualTo(timeTool.call(args));
		assertThat(recorder.calls()).singleElement().satisfies(call -> {
			assertThat(call.toolName()).isEqualTo("currentTime");
			assertThat(call.arguments()).isEqualTo(args);
			assertThat(call.error()).isFalse();
			assertThat(call.latency().isNegative()).isFalse();
		});
	}

	@Test
	void recordsAnErrorResultAsError() {
		String result = recording.call("{\"zone\":\"Mars/Olympus\"}");

		assertThat(result).contains("Error: invalid time zone");
		assertThat(recorder.calls()).singleElement().satisfies(call -> assertThat(call.error()).isTrue());
	}

	@Test
	void detectsErrorsWithOrWithoutJsonQuotes() {
		assertThat(RecordingToolCallback.isError("Error: bad zone")).isTrue();
		assertThat(RecordingToolCallback.isError("\"Error: bad zone\"")).isTrue();
		assertThat(RecordingToolCallback.isError("\"2026-10-05T21:00:00+09:00\"")).isFalse();
		assertThat(RecordingToolCallback.isError(null)).isFalse();
	}

	@Test
	void recordsCallsMadeWithAToolContext() {
		recording.call("{\"zone\":\"Europe/Rome\"}", new ToolContext(Map.of()));

		assertThat(recorder.calls()).hasSize(1);
	}

	@Test
	void exposesTheDelegateDefinitionAndMetadata() {
		assertThat(recording.getToolDefinition()).isSameAs(timeTool.getToolDefinition());
		assertThat(recording.getToolMetadata()).isSameAs(timeTool.getToolMetadata());
	}

	@Test
	void recordsAThrowingDelegateAsErrorAndRethrows() {
		ToolCallback failing = new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder().name("boom").description("fails").inputSchema("{}").build();
			}

			@Override
			public String call(String toolInput) {
				throw new IllegalStateException("boom");
			}
		};
		RecordingToolCallback wrapped = new RecordingToolCallback(failing, recorder);

		assertThatThrownBy(() -> wrapped.call("{}")).isInstanceOf(IllegalStateException.class);
		assertThat(recorder.calls()).singleElement().satisfies(call -> {
			assertThat(call.toolName()).isEqualTo("boom");
			assertThat(call.error()).isTrue();
		});
	}

	@Test
	void clearDropsRecordedCallsAndCallsIsASnapshot() {
		recording.call("{\"zone\":\"Asia/Tokyo\"}");
		var snapshot = recorder.calls();

		recorder.clear();

		assertThat(recorder.calls()).isEmpty();
		assertThat(snapshot).hasSize(1);
	}

}
