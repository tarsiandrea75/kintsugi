package dev.kintsugi.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
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
		this.runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("spring.ai.mcp.server.protocol=STATELESS")
				.hasMessageContaining("but the protocol is null");
		});
	}

	/** The guard reads the value as {@code @ConditionalOnProperty} and relaxed enum binding do: ignoring case. */
	@ParameterizedTest
	@ValueSource(strings = { "STATELESS", "stateless", "Stateless" })
	void statelessInAnyCaseStartsWithTheValidatedTransport(String protocol) {
		this.runner.withPropertyValues("spring.ai.mcp.server.protocol=" + protocol)
			.withBean("mcpServerJsonMapper", JsonMapper.class, () -> JsonMapper.builder().build())
			.withBean(McpServerStreamableHttpProperties.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(WebMvcStatelessServerTransport.class);
			});
	}

}
