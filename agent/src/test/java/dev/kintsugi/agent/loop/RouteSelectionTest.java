package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import dev.kintsugi.agent.AgentApplication;

/**
 * {@code kintsugi.agent.route} selects the chat model's provider through {@code spring.ai.model.chat}, and a route that
 * cannot run stops the start naming what is missing (agent spec, "Routes").
 */
class RouteSelectionTest {

	@Test
	void noRouteIsTheLocalRouteOnOllama() {
		StandardEnvironment environment = environment(Map.of());

		new RouteEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("ollama");
	}

	@Test
	void theCloudRouteIsAnthropic() {
		StandardEnvironment environment = environment(
				Map.of("kintsugi.agent.route", "cloud", "spring.ai.anthropic.api-key", "test-only-not-a-key"));

		new RouteEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("anthropic");
	}

	@Test
	void theRouteDecidesTheProviderEvenWhenOneIsSet() {
		StandardEnvironment environment = environment(Map.of("spring.ai.model.chat", "anthropic"));

		new RouteEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("ollama");
	}

	@Test
	void anUnknownRouteStopsTheStartNamingTheProperty() {
		assertThatThrownBy(() -> start("--kintsugi.agent.route=gpu"))
			.hasStackTraceContaining("kintsugi.agent.route must be local or cloud, got \"gpu\"");
	}

	@Test
	void theCloudRouteWithoutAKeyStopsTheStartNamingTheVariable() {
		assertThatThrownBy(() -> start("--kintsugi.agent.route=cloud", "--spring.ai.anthropic.api-key="))
			.hasStackTraceContaining("ANTHROPIC_API_KEY");
	}

	@Test
	void aBlankKeyIsNoKey() {
		StandardEnvironment environment = environment(
				Map.of("kintsugi.agent.route", "cloud", "spring.ai.anthropic.api-key", "  "));

		assertThatThrownBy(() -> new RouteEnvironmentPostProcessor().postProcessEnvironment(environment,
				new SpringApplication()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ANTHROPIC_API_KEY");
	}

	/**
	 * Starts the real application with these command-line arguments, which outrank {@code application.yaml} and the
	 * environment; both failures happen before any bean, so no database is needed.
	 */
	private static void start(String... args) {
		new SpringApplicationBuilder(AgentApplication.class).web(WebApplicationType.NONE).run(args).close();
	}

	private static StandardEnvironment environment(Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

}
