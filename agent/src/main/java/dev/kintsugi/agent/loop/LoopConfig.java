package dev.kintsugi.agent.loop;

import java.time.Clock;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.lab.LabTools;

/** The loop is the web profile's {@code IncidentRunner}; the {@code demo} profile has neither. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties({ ModelRouteProperties.class, LoopSettings.class })
class LoopConfig {

	@Bean
	ModelRouter modelRouter(ChatModel chatModel, ModelRouteProperties route) {
		return new OllamaModelRouter(chatModel, route);
	}

	@Bean
	AgentLoop agentLoop(IncidentRepository incidents, ModelRouter router, LabTools labTools, LoopSettings settings,
			Clock clock) {
		return new AgentLoop(incidents, router, labTools, settings, clock);
	}

}
