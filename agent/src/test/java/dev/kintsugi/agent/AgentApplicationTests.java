package dev.kintsugi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.loop.AgentRoute;
import dev.kintsugi.agent.loop.ModelRouter;
import dev.kintsugi.agent.loop.ModelTask;

@SpringBootTest
class AgentApplicationTests {

	@Autowired
	OllamaChatModel chatModel;

	@Autowired
	ModelRouter router;

	@Autowired
	ApplicationContext context;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void contextLoads() {
	}

	@Test
	void ollamaDefaultsAreBoundFromConfiguration() {
		OllamaChatOptions options = (OllamaChatOptions) chatModel.getDefaultOptions();

		assertThat(options.getModel()).isEqualTo("granite4.1:8b");
		assertThat(options.getTemperature()).isEqualTo(0.0);
		assertThat(options.getNumCtx()).isEqualTo(8192);
	}

	/** With no route set, the route is local: Ollama's model, and Anthropic's auto-configuration stays off. */
	@Test
	void theDefaultRouteIsTheLocalOne() {
		assertThat(router.route(ModelTask.ACT).route()).isEqualTo(AgentRoute.LOCAL);
		assertThat(router.route(ModelTask.REPORT).chatModel()).isSameAs(chatModel);
		assertThat(context.getBeanNamesForType(AnthropicChatModel.class)).isEmpty();
	}

}
