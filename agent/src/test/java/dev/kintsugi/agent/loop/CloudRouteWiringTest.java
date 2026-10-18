package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;

/**
 * {@code kintsugi.agent.route=cloud} wires one chat model, Anthropic's, and the router's one route is the cloud route
 * with its own deadlines (agent spec, "Routes"). The key is a test value: the context builds the client, but no test
 * calls it.
 */
@SpringBootTest(properties = { "kintsugi.agent.route=cloud", "spring.ai.anthropic.api-key=test-only-not-a-key" })
class CloudRouteWiringTest {

	@Autowired
	ApplicationContext context;

	@Autowired
	ModelRouter router;

	@Autowired
	AnthropicConnectionProperties connection;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void theOnlyChatModelIsAnthropics() {
		assertThat(this.context.getBeansOfType(ChatModel.class)).hasSize(1);
		assertThat(this.context.getBeanNamesForType(AnthropicChatModel.class)).hasSize(1);
		assertThat(this.context.getBeanNamesForType(OllamaChatModel.class)).isEmpty();
	}

	@Test
	void everyTaskGoesToTheCloudRoute() {
		for (ModelTask task : ModelTask.values()) {
			ModelRoute route = this.router.route(task);

			assertThat(route).isInstanceOf(AnthropicRoute.class);
			assertThat(route.route()).isEqualTo(AgentRoute.CLOUD);
			assertThat(route.model()).isEqualTo("claude-haiku-5-5");
			assertThat(route.chatModel()).isInstanceOf(AnthropicChatModel.class);
			assertThat(route.callTimeouts())
				.isEqualTo(new CallTimeouts(Duration.ofSeconds(60), Duration.ofSeconds(90)));
			assertThat(route.numPredict()).isEqualTo(new NumPredict(384, 160, 512));
		}
	}

	@Test
	void theClientWaitsForTheLongestCallDeadlineAndNeverRetries() {
		assertThat(this.connection.getTimeout()).isEqualTo(Duration.ofSeconds(90));
		assertThat(this.connection.getMaxRetries()).isZero();
	}

}
