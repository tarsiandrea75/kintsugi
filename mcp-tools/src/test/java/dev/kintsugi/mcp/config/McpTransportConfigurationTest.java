package dev.kintsugi.mcp.config;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Origin validation is wired only into the STATELESS transport, so any other protocol must stop the application
 * instead of starting without validation.
 */
class McpTransportConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(McpTransportConfiguration.class);

	@Test
	void anotherProtocolFailsTheStartup() {
		this.runner.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("spring.ai.mcp.server.protocol=STATELESS");
		});
	}

	@Test
	void aMissingProtocolFailsTheStartup() {
		this.runner.run(context -> assertThat(context).hasFailed());
	}

}
