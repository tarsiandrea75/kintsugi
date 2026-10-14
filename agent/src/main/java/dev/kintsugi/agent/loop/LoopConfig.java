package dev.kintsugi.agent.loop;

import java.time.Clock;
import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.lab.LabTools;

/** The loop is the web profile's {@code IncidentRunner}; the {@code demo} profile has neither. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties({ ModelRouteProperties.class, LoopSettings.class })
class LoopConfig {

	/** The connect timeout of the loop's HTTP client, as {@code spring.http.clients.connect-timeout}. */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

	@Bean
	ModelRouter modelRouter(ChatModel chatModel, ModelRouteProperties route) {
		return new OllamaModelRouter(chatModel, route);
	}

	@Bean
	AgentLoop agentLoop(IncidentRepository incidents, ModelRouter router, LabTools labTools, LoopSettings settings,
			Clock clock) {
		return new AgentLoop(incidents, router, labTools, settings, clock);
	}

	/**
	 * The loop's own HTTP read timeout, applied to the auto-configured {@code RestClient.Builder} that Ollama's client is
	 * built from, in the web profile only; the {@code demo} profile keeps {@code spring.http.clients.read-timeout}. It
	 * is the longer of the two waits the loop allows a request, one model call and the warm-up, so that neither is cut
	 * by the HTTP client before the loop's own deadline ends it (agent spec, "Shared rules").
	 */
	@Bean
	RestClientCustomizer loopReadTimeout(ModelRouteProperties route, LoopSettings settings) {
		HttpClientSettings timeouts = HttpClientSettings.defaults()
			.withTimeouts(CONNECT_TIMEOUT, readTimeout(route, settings));
		return builder -> builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts));
	}

	static Duration readTimeout(ModelRouteProperties route, LoopSettings settings) {
		return route.callTimeout().compareTo(settings.warmUpTimeout()) >= 0 ? route.callTimeout()
				: settings.warmUpTimeout();
	}

}
