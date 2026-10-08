package dev.kintsugi.mcp.config;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code kintsugi_agent} password has no default: it comes only from the environment, so the application refuses
 * to start without it instead of trying a password nobody set.
 */
class DatabasePropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(EvidenceConfiguration.class)
		.withPropertyValues("kintsugi.mcp.postgres.host=localhost", "kintsugi.mcp.postgres.port=5432",
				"kintsugi.mcp.postgres.username=kintsugi_agent", "kintsugi.mcp.postgres.connection-timeout=1s",
				"kintsugi.mcp.postgres.maximum-pool-size=1");

	@Test
	void aMissingPasswordFailsTheStartup() {
		this.runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("password");
		});
	}

	@Test
	void anEmptyPasswordFailsTheStartup() {
		this.runner.withPropertyValues("kintsugi.mcp.postgres.password=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("password");
		});
	}

	@Test
	void aPasswordStartsThePools() {
		this.runner.withPropertyValues("kintsugi.mcp.postgres.password=secret").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(DatabaseProperties.class).password()).isEqualTo("secret");
		});
	}

}
