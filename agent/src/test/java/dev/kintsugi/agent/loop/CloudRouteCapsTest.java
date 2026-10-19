package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;

/**
 * The cloud route has its own answer caps, {@code kintsugi.agent.cloud.num-predict} (agent spec, "Answer caps"): an
 * override of its report cap reaches the report call's {@code maxTokens}, and the local route keeps its own caps. The
 * key is a test value: the context builds the client, but no test calls it.
 */
@SpringBootTest(properties = { "kintsugi.agent.route=cloud", "spring.ai.anthropic.api-key=test-only-not-a-key",
		"kintsugi.agent.cloud.num-predict.report=768" })
class CloudRouteCapsTest {

	@Autowired
	ModelRouter router;

	@Autowired
	ModelRouteProperties local;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void anOverriddenCloudReportCapReachesTheReportCall() {
		ModelRoute route = this.router.route(ModelTask.REPORT);
		int cap = route.numPredict().of(ModelTask.REPORT, false);

		AnthropicChatOptions options = (AnthropicChatOptions) route.options(cap, Duration.ofSeconds(90), List.of(),
				null);

		assertThat(route.numPredict()).isEqualTo(new NumPredict(384, 160, 768));
		assertThat(options.getMaxTokens()).isEqualTo(768);
	}

	@Test
	void theLocalRouteKeepsItsOwnCaps() {
		assertThat(this.local.numPredict()).isEqualTo(new NumPredict(384, 160, 512));
	}

}
