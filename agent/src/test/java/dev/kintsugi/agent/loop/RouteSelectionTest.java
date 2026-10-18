package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import dev.kintsugi.agent.AgentApplication;

/**
 * {@code kintsugi.agent.route} selects the chat model's provider through {@code spring.ai.model.chat}, and a route that
 * cannot run stops the start naming what is missing (agent spec, "Routes"). The environments hold only what each test
 * gives them, never the shell's variables, so a developer's {@code KINTSUGI_AGENT_ROUTE} cannot change the outcome.
 */
class RouteSelectionTest {

	@Test
	void noRouteIsTheLocalRouteOnOllama() {
		MockEnvironment environment = environment(Map.of());

		new RouteEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("ollama");
	}

	/** The packaged application.yaml, with no {@code KINTSUGI_AGENT_ROUTE}, selects the local route. */
	@Test
	void thePackagedDefaultIsTheLocalRoute() throws IOException {
		MockEnvironment environment = environment(Map.of());
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);

		new RouteEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("kintsugi.agent.route")).isEqualTo("local");
		assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("ollama");
	}

	@Test
	void theCloudRouteIsAnthropic() {
		MockEnvironment environment = environment(
				Map.of("kintsugi.agent.route", "cloud", "spring.ai.anthropic.api-key", "test-only-not-a-key"));

		new RouteEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("anthropic");
	}

	@Test
	void theRouteDecidesTheProviderEvenWhenOneIsSet() {
		MockEnvironment environment = environment(Map.of("spring.ai.model.chat", "anthropic"));

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
		MockEnvironment environment = environment(
				Map.of("kintsugi.agent.route", "cloud", "spring.ai.anthropic.api-key", "  "));

		assertThatThrownBy(() -> new RouteEnvironmentPostProcessor().postProcessEnvironment(environment,
				new SpringApplication()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ANTHROPIC_API_KEY");
	}

	/**
	 * Starts the real application with these command-line arguments, which outrank {@code application.yaml} and the
	 * shell's variables; both failures happen before any bean, so no database is needed.
	 */
	private static void start(String... args) {
		new SpringApplicationBuilder(AgentApplication.class).web(WebApplicationType.NONE).run(args).close();
	}

	/** An environment with these properties only: unlike {@code StandardEnvironment}, no system environment. */
	private static MockEnvironment environment(Map<String, Object> properties) {
		MockEnvironment environment = new MockEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

}
