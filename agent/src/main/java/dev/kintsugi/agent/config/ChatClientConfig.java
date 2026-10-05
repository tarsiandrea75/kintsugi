package dev.kintsugi.agent.config;

import dev.kintsugi.agent.tools.TimeTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

	@Bean
	ChatClient chatClient(ChatClient.Builder builder, TimeTools timeTools) {
		return builder.defaultTools(timeTools).build();
	}

}
