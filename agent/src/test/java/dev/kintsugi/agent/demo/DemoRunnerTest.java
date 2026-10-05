package dev.kintsugi.agent.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(OutputCaptureExtension.class)
class DemoRunnerTest {

	private static final String BASE_URL = "http://localhost:11434";

	private static DemoRunner runnerAnswering(ChatModel model) {
		return new DemoRunner(ChatClient.create(model), BASE_URL);
	}

	@Test
	void printsTheModelAnswer(CapturedOutput output) throws Exception {
		DemoRunner runner = runnerAnswering(
				prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("It is 09:00 in Buenos Aires.")))));

		runner.run(new DefaultApplicationArguments());

		assertThat(output.getOut()).contains("09:00");
		assertThat(runner.getExitCode()).isZero();
	}

	@Test
	void reportsUnreachableOllamaWithoutStackTrace(CapturedOutput output) throws Exception {
		DemoRunner runner = runnerAnswering(prompt -> {
			throw new RuntimeException("wrapped",
					new ResourceAccessException("I/O error", new ConnectException("Connection refused")));
		});

		runner.run(new DefaultApplicationArguments());

		assertThat(output.getErr()).contains("not reachable").contains(BASE_URL).contains("ollama serve");
		assertThat(output.getAll()).doesNotContain("\tat ");
		assertThat(runner.getExitCode()).isEqualTo(1);
	}

	@Test
	void rethrowsErrorsThatAreNotConnectionFailures() {
		DemoRunner runner = runnerAnswering(prompt -> {
			throw new IllegalStateException("boom");
		});

		assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class);
	}

}
