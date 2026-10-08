package dev.kintsugi.mcp.config;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Host allow-list, and Origin validation, which is wired only into the STATELESS transport, so any other protocol must stop the application
 * instead of starting without validation.
 */
class McpTransportConfigurationTest {

	/** The default of {@code kintsugi.mcp.allowed-hosts} in {@code application.properties}. */
	private static final List<String> COMPOSE_HOSTS = List.of("mcp-tools:8085", "localhost:18085", "127.0.0.1:18085");

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

	@ParameterizedTest
	@ValueSource(strings = { "", " , " })
	void anEmptyHostAllowListFailsTheStartup(String hosts) {
		this.runner.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "kintsugi.mcp.allowed-hosts=" + hosts)
			.withBean("mcpServerJsonMapper", JsonMapper.class, () -> JsonMapper.builder().build())
			.withBean(McpServerStreamableHttpProperties.class)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("kintsugi.mcp.allowed-hosts");
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "mcp-tools:8085", "localhost:18085", "127.0.0.1:18085" })
	void theComposeHostsAreAccepted(String host) {
		ServerTransportSecurityValidator validator = McpTransportConfiguration.securityValidator(COMPOSE_HOSTS);

		assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of(host)))).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "evil.example", "evil.example:18085", "localhost:8085", "mcp-tools:18085" })
	void anyOtherHostIsRejectedWith421(String host) {
		ServerTransportSecurityValidator validator = McpTransportConfiguration.securityValidator(COMPOSE_HOSTS);

		assertThatThrownBy(() -> validator.validateHeaders(Map.of("Host", List.of(host))))
			.isInstanceOfSatisfying(ServerTransportSecurityException.class,
					ex -> assertThat(ex.getStatusCode()).isEqualTo(421));
	}

	@Test
	void anOriginIsRejectedWith403EvenWithAnAllowedHost() {
		ServerTransportSecurityValidator validator = McpTransportConfiguration.securityValidator(COMPOSE_HOSTS);

		assertThatThrownBy(() -> validator.validateHeaders(
				Map.of("Origin", List.of("http://localhost:18085"), "Host", List.of("localhost:18085"))))
			.isInstanceOfSatisfying(ServerTransportSecurityException.class,
					ex -> assertThat(ex.getStatusCode()).isEqualTo(403));
	}

	/** The guard reads the value as {@code @ConditionalOnProperty} and relaxed enum binding do: ignoring case. */
	@ParameterizedTest
	@ValueSource(strings = { "STATELESS", "stateless", "Stateless" })
	void statelessInAnyCaseStartsWithTheValidatedTransport(String protocol) {
		this.runner
			.withPropertyValues("spring.ai.mcp.server.protocol=" + protocol, "kintsugi.mcp.allowed-hosts=mcp-tools:8085")
			.withBean("mcpServerJsonMapper", JsonMapper.class, () -> JsonMapper.builder().build())
			.withBean(McpServerStreamableHttpProperties.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(WebMvcStatelessServerTransport.class);
			});
	}

}
