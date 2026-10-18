package dev.kintsugi.agent.loop;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Derives Spring AI's provider, {@code spring.ai.model.chat}, from {@code kintsugi.agent.route}, so that one input
 * selects the route (agent spec, "Routes"). Both chat auto-configurations of Spring AI 2.0.1 are on when the property
 * is missing, so it is always set: {@code ollama} for {@code local}, {@code anthropic} for {@code cloud}, and only one
 * {@code ChatModel} exists. A property derived in YAML could not map one value to another, and a profile per route
 * would need a second input or a placeholder in {@code spring.profiles.include}.
 *
 * <p>
 * It runs after {@code application.yaml} is loaded, and stops the start before any bean: on an unknown route naming
 * {@code kintsugi.agent.route}, and on the cloud route without a key naming {@code ANTHROPIC_API_KEY}, instead of
 * failing later at the first call.
 */
public class RouteEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String ROUTE = "kintsugi.agent.route";

	static final String PROVIDER = "spring.ai.model.chat";

	static final String API_KEY = "spring.ai.anthropic.api-key";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		AgentRoute route = AgentRoute.of(environment.getProperty(ROUTE, AgentRoute.LOCAL.value()));
		if (route == AgentRoute.CLOUD) {
			String key = environment.getProperty(API_KEY);
			if (key == null || key.isBlank()) {
				throw new IllegalStateException(
						"kintsugi.agent.route=cloud needs ANTHROPIC_API_KEY: set it in infra/.env, or use the local route");
			}
		}
		environment.getPropertySources()
			.addFirst(new MapPropertySource("kintsugiAgentRoute", Map.of(PROVIDER, route.provider())));
	}

	/** After the config data, which {@code ConfigDataEnvironmentPostProcessor} loads early. */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
