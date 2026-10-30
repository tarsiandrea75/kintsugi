package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;

/**
 * The local route's per-run overrides reach the route the loop calls (agent spec, "Routes", "Answer caps", "Budgets";
 * odd/tasks/qwen-dev.md T3, AC2): the model, Ollama's {@code think} field, the report's answer cap and the report's
 * deadline, and the loop's HTTP read timeout follows the longer deadline, so the client never cuts a report the loop
 * still waits for. The context builds the Ollama client, but no test calls it.
 */
@SpringBootTest(properties = { "kintsugi.agent.model.name=qwen3.5:9b", "kintsugi.agent.model.think=true",
		"kintsugi.agent.model.num-predict.report=2048", "kintsugi.agent.model.call-timeout.report=1800s" })
class LocalRouteOverridesTest {

	@Autowired
	ModelRouter router;

	@Autowired
	ModelRouteProperties local;

	@Autowired
	LoopSettings settings;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void theOverridesReachTheReportCall() {
		ModelRoute route = this.router.route(ModelTask.REPORT);
		int cap = route.numPredict().of(ModelTask.REPORT, false);

		OllamaChatOptions options = (OllamaChatOptions) route.options(cap, route.callTimeouts().of(ModelTask.REPORT),
				List.of(), null);

		assertThat(route).isInstanceOf(OllamaRoute.class);
		assertThat(route.model()).isEqualTo("qwen3.5:9b");
		assertThat(route.numPredict()).isEqualTo(new NumPredict(384, 160, 2048));
		assertThat(route.callTimeouts()).isEqualTo(new CallTimeouts(Duration.ofSeconds(600), Duration.ofSeconds(1800)));
		assertThat(options.getModel()).isEqualTo("qwen3.5:9b");
		assertThat(options.getNumPredict()).isEqualTo(2048);
		assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.ENABLED);
		assertThat(((OllamaChatOptions) route.warmUpOptions().orElseThrow()).getModel()).isEqualTo("qwen3.5:9b");
	}

	@Test
	void theLoopsReadTimeoutFollowsTheOverriddenReportDeadline() {
		assertThat(LoopConfig.readTimeout(this.local, this.settings)).isEqualTo(Duration.ofSeconds(1800));
	}

}
