package dev.kintsugi.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import dev.kintsugi.agent.AgentDatabase;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ChatClientWiringTest {

	@MockitoBean
	ChatModel chatModel;

	@TestBean
	Clock clock;

	@Autowired
	ChatClient chatClient;

	@Autowired
	ApplicationContext context;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
	}

	@Test
	void promptCarriesCurrentTimeToolBackedByContextClock() {
		when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class)))
			.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

		chatClient.prompt().user("What time is it in Rome?").call().content();

		ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
		verify(chatModel).call(captor.capture());
		ToolCallingChatOptions options = (ToolCallingChatOptions) captor.getValue().getOptions();
		ToolCallback callback = options.getToolCallbacks()
			.stream()
			.filter(tool -> tool.getToolDefinition().name().equals("currentTime"))
			.findFirst()
			.orElseThrow();
		assertThat(callback.call("{\"zone\":\"Europe/Rome\"}")).contains("2026-10-05T14:00:00+02:00");
	}

	@Test
	void demoRunnerIsNotPresentWithoutDemoProfile() {
		assertThat(context.containsBean("demoRunner")).isFalse();
	}

}
