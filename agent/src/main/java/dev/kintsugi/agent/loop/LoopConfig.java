package dev.kintsugi.agent.loop;

import java.time.Clock;
import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;

import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.lab.LabTools;

/** The loop is the web profile's {@code IncidentRunner}; the {@code demo} profile has neither. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties({ ModelRouteProperties.class, CloudRouteProperties.class, LoopSettings.class })
class LoopConfig {

	/** The connect timeout of the loop's HTTP client, as {@code spring.http.clients.connect-timeout}. */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

	/**
	 * The router's one route, from {@code kintsugi.agent.route} (cloud-route T3): {@code local}, the default, on Ollama,
	 * or {@code cloud} on Anthropic. {@link RouteEnvironmentPostProcessor} has already checked the value and made the
	 * matching provider the only {@code ChatModel}.
	 */
	@Bean
	ModelRouter modelRouter(ChatModel chatModel, ModelRouteProperties local, CloudRouteProperties cloud,
			@Value("${kintsugi.agent.route:local}") String route) {
		return new OneRouteModelRouter(switch (AgentRoute.of(route)) {
			case LOCAL -> new OllamaRoute(local.name(), chatModel, local.temperature(), local.numCtx(),
					local.callTimeout(), local.numPredict());
			case CLOUD -> new AnthropicRoute(cloud.name(), chatModel, cloud.callTimeout(), cloud.numPredict());
		});
	}

	/**
	 * The loop, in the mode of {@code kintsugi.agent.mode} (T5a): {@code explorer}, the default, or {@code dossier}; any
	 * other value stops the agent's start with the property's name.
	 */
	@Bean
	AgentLoop agentLoop(IncidentRepository incidents, ModelRouter router, LabTools labTools, LoopSettings settings,
			Clock clock, @Value("${kintsugi.agent.mode:explorer}") String mode) {
		return new AgentLoop(incidents, router, labTools, settings, clock, AgentMode.of(mode));
	}

	/**
	 * The loop's own HTTP read timeout, applied to the auto-configured {@code RestClient.Builder} that Ollama's client is
	 * built from, in the web profile only; the {@code demo} profile keeps {@code spring.http.clients.read-timeout}. It
	 * is the longest wait the loop allows a request, the longer call deadline ({@code ACT} or {@code REPORT}) or the
	 * warm-up, so that none is cut
	 * by the HTTP client before the loop's own deadline ends it (agent spec, "Shared rules"). The cloud route's client
	 * is Anthropic's SDK, not a {@code RestClient}: its timeout is {@code spring.ai.anthropic.timeout}.
	 *
	 * <p>
	 * The request body is buffered, so every request carries a {@code Content-Length} instead of a chunked body. When
	 * the loop abandons a call at its deadline, the interrupt closes the connection; but Ollama's server notices a
	 * client going away only after it has read the request body to its end, and with a chunked body whose last chunk
	 * arrives apart it never does, so an abandoned call would run to its end and delay every later call (agent spec,
	 * "An abandoned call is aborted"). The bodies are a few kilobytes of JSON, so buffering costs nothing.
	 */
	@Bean
	RestClientCustomizer loopReadTimeout(ModelRouteProperties route, LoopSettings settings) {
		HttpClientSettings timeouts = HttpClientSettings.defaults()
			.withTimeouts(CONNECT_TIMEOUT, readTimeout(route, settings));
		return builder -> builder.requestFactory(
				new BufferingClientHttpRequestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts)));
	}

	static Duration readTimeout(ModelRouteProperties route, LoopSettings settings) {
		Duration longestCall = route.callTimeout().longest();
		return longestCall.compareTo(settings.warmUpTimeout()) >= 0 ? longestCall : settings.warmUpTimeout();
	}

}
