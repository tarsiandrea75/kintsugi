package dev.kintsugi.agent.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.kintsugi.agent.smoke.SmokeReport.Verdict;
import dev.kintsugi.agent.tools.TimeTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Tool-calling smoke test over 10 fixed prompts. It scores the recorded tool calls, writes
 * {@code target/smoke-test/<model>.md} and fails when the adoption bar is not met. Needs a local
 * Ollama. Run with {@code ./mvnw test -Plive -Dkintsugi.model=<model>}; without the property it
 * uses the configured model. A prompt that throws counts as failed and its error goes in the report.
 * If Ollama is down, every prompt fails and so does the adoption bar; nothing is skipped.
 */
@Tag("live")
@SpringBootTest(properties = "kintsugi.agent.route=local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveToolCallingSmokeTest {

	static final String MODEL_PROPERTY = "kintsugi.model";

	static final List<PromptCase> PROMPTS = List.of(
			PromptCase.positive("P1", "What time is it in Buenos Aires?", "09:00"),
			PromptCase.positive("P2", "What is the current time in Tokyo?", "21:00"),
			PromptCase.positive("P3", "Tell me the current date and time in New York.", "2026-10-05", "08:00"),
			PromptCase.positive("P4", "Is it already evening in Tokyo?", "yes"),
			PromptCase.positive("P5", "Would a shop in Rome that opens at 9am be open right now?", "yes"),
			PromptCase.positive("P6", "What time is it in Portland?"),
			PromptCase.positive("P7", "What time is it in London and in Los Angeles?", "13:00", "05:00")
				.withMinCalls(2),
			PromptCase.positive("P8", "How many hours ahead of New York is Tokyo right now?", "13"),
			PromptCase.negative("N1", "What is the capital of Japan?", "Tokyo"),
			PromptCase.negative("N2", "How many minutes are in a day?", "1440"));

	@TestBean
	Clock clock;

	@Autowired
	ChatClient.Builder chatClientBuilder;

	@Autowired
	TimeTools timeTools;

	@Autowired
	Environment environment;

	@Value("${spring.ai.ollama.chat.model}")
	String model;

	private final ToolCallRecorder recorder = new ToolCallRecorder();

	private final List<PromptResult> results = new ArrayList<>();

	private ChatClient chatClient;

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
	}

	@DynamicPropertySource
	static void model(DynamicPropertyRegistry registry) {
		String override = System.getProperty(MODEL_PROPERTY);
		if (override != null && !override.isBlank()) {
			registry.add("spring.ai.ollama.chat.model", override::strip);
		}
	}

	static List<PromptCase> prompts() {
		return PROMPTS;
	}

	@BeforeAll
	void wrapTools() {
		ToolCallback[] recording = Arrays.stream(ToolCallbacks.from(timeTools))
			.map(callback -> new RecordingToolCallback(callback, recorder))
			.toArray(ToolCallback[]::new);
		chatClient = chatClientBuilder.defaultToolCallbacks(recording).build();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("prompts")
	void ask(PromptCase prompt) {
		recorder.clear();
		long start = System.nanoTime();
		try {
			String answer = chatClient.prompt().user(prompt.prompt()).call().content();
			results.add(new PromptResult(prompt, recorder.calls(), answer, elapsedSince(start)));
		}
		catch (RuntimeException ex) {
			results.add(PromptResult.failed(prompt, recorder.calls(), ex, elapsedSince(start)));
		}
	}

	private static Duration elapsedSince(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos);
	}

	@AfterAll
	void reportAndAssertAdoptionBar() throws IOException {
		assertThat(results).as("prompts answered (is Ollama running with %s?)", model).hasSize(PROMPTS.size());

		String temperature = environment.getProperty("spring.ai.ollama.chat.temperature", "unset");
		SmokeReport report = new SmokeReport(model, temperature, results);
		Path path = Path.of("target", "smoke-test", SmokeReport.fileName(model));
		Files.createDirectories(path.getParent());
		Files.writeString(path, report.toMarkdown());

		long threw = results.stream().filter(r -> r.error() != null).count();
		assertThat(report.verdict())
			.as("adoption bar for %s (%d of %d prompts threw; is Ollama running?), see %s", model, threw,
					results.size(), path.toAbsolutePath())
			.isEqualTo(Verdict.ADOPT);
	}

}
