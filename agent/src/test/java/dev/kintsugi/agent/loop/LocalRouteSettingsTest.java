package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The local route's settings as the packaged application.yaml binds them, with and without the environment variables
 * a measurement sets (agent spec, "Routes", "Answer caps", "Budgets"; odd/tasks/qwen-dev.md T3): with none, the
 * settings of every run before the overrides (AC1); with them, the overridden model, thinking, report cap and report
 * deadline. The environment is the test's own, never the shell's.
 */
class LocalRouteSettingsTest {

	@Test
	void withNoOverrideTheLocalRouteIsTodays() throws IOException {
		ModelRouteProperties local = bind(Map.of());

		assertThat(local).isEqualTo(new ModelRouteProperties("granite4.1:8b", 0, 8192,
				new CallTimeouts(Duration.ofSeconds(600), Duration.ofSeconds(600)), new NumPredict(384, 160, 512),
				null));
	}

	@Test
	void theEnvironmentOverridesTheModelThinkingTheReportCapAndTheReportDeadline() throws IOException {
		ModelRouteProperties local = bind(Map.of("KINTSUGI_AGENT_MODEL_NAME", "qwen3.5:9b", "KINTSUGI_AGENT_MODEL_THINK",
				"false", "KINTSUGI_AGENT_MODEL_NUM_PREDICT_REPORT", "1024", "KINTSUGI_AGENT_MODEL_CALL_TIMEOUT_REPORT",
				"900s"));

		assertThat(local).isEqualTo(new ModelRouteProperties("qwen3.5:9b", 0, 8192,
				new CallTimeouts(Duration.ofSeconds(600), Duration.ofSeconds(900)), new NumPredict(384, 160, 1024),
				false));
		assertThat(LoopConfig.readTimeout(local, new LoopSettings(15, Duration.ofMinutes(45), Duration.ofSeconds(300))))
			.isEqualTo(Duration.ofSeconds(900));
	}

	/**
	 * {@code KINTSUGI_AGENT_MODEL_NUM_CTX}, set by {@code replay-agent.sh --num-ctx} (context-full-cut T3), binds to
	 * {@code kintsugi.agent.model.num-ctx} and reaches the local route's call options and its warm-up; without it, the
	 * window is 8192.
	 */
	@Test
	void theEnvironmentOverridesTheContextWindowOfTheCallsAndTheWarmUp() throws IOException {
		assertThat(numCtxOf(bind(Map.of("KINTSUGI_AGENT_MODEL_NUM_CTX", "12288")))).containsExactly(12288, 12288);
		assertThat(numCtxOf(bind(Map.of()))).containsExactly(8192, 8192);
	}

	@Test
	void anEmptyThinkSendsNoThinkField() throws IOException {
		assertThat(bind(Map.of("KINTSUGI_AGENT_MODEL_THINK", "")).think()).isNull();
		assertThat(bind(Map.of("KINTSUGI_AGENT_MODEL_THINK", "true")).think()).isTrue();
	}

	/**
	 * The {@code num_ctx} of a report call and of the warm-up on the local route {@link LoopConfig} builds from
	 * {@code local}.
	 */
	private static List<Integer> numCtxOf(ModelRouteProperties local) {
		ModelRoute route = new LoopConfig().modelRouter(new ScriptedChatModel(), local, null, "local")
			.route(ModelTask.REPORT);
		OllamaChatOptions call = (OllamaChatOptions) route.options(512, Duration.ofSeconds(600), List.of(), null);
		OllamaChatOptions warmUp = (OllamaChatOptions) route.warmUpOptions().orElseThrow();
		return List.of(call.getNumCtx(), warmUp.getNumCtx());
	}

	/** {@code kintsugi.agent.model} from the packaged application.yaml under {@code env} as the only environment. */
	private static ModelRouteProperties bind(Map<String, Object> env) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources()
			.addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					env));
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		return Binder.get(environment).bind("kintsugi.agent.model", ModelRouteProperties.class).get();
	}

}
