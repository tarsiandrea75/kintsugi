package dev.kintsugi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AgentApplicationTests {

	@Autowired
	OllamaChatModel chatModel;

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

}
