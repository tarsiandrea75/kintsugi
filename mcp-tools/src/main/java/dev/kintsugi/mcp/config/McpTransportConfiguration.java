package dev.kintsugi.mcp.config;

import java.util.List;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Replaces Spring AI's stateless WebMVC transport to turn on Origin validation (ADR-004).
 *
 * <p>
 * Spring AI 2.0.1 builds the transport with the SDK's no-op security validator and exposes no property to change
 * it. Its auto-configured bean is declared {@code @ConditionalOnMissingBean} without attributes, which matches by the
 * method's return type, {@link WebMvcStatelessServerTransport}: any bean of that type makes it back off, whatever its
 * name. The bean below keeps the same name only for readability.
 *
 * <p>
 * The validator is wired into the STATELESS transport only. Spring AI picks a different transport class for the
 * other protocols, so a protocol switch would start the server without validation. {@link #statelessProtocolGuard}
 * stops the application instead.
 */
@Configuration(proxyBeanMethods = false)
public class McpTransportConfiguration {

	static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";

	/**
	 * Origins allowed to call {@code /mcp}: none. The only client is the agent, a server process that sends no
	 * {@code Origin} header, so a request that carries one comes from a browser and is rejected with 403. That blocks
	 * DNS-rebinding attacks from a page open in the developer's browser. Set explicitly so the policy does not depend
	 * on the builder's defaults.
	 */
	static final List<String> ALLOWED_ORIGINS = List.of();

	@Bean
	@ConditionalOnProperty(name = PROTOCOL_PROPERTY, havingValue = "STATELESS")
	WebMvcStatelessServerTransport webMvcStatelessServerTransport(@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties serverProperties) {
		return WebMvcStatelessServerTransport.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.messageEndpoint(serverProperties.getMcpEndpoint())
			.securityValidator(DefaultServerTransportSecurityValidator.builder().allowedOrigins(ALLOWED_ORIGINS).build())
			.build();
	}

	/**
	 * Fails the startup unless the protocol is STATELESS, the only one whose transport carries the validator. The
	 * value is compared ignoring case, like {@code @ConditionalOnProperty} above and Spring's relaxed enum binding, so
	 * {@code stateless} keeps both the validated transport and a running server.
	 */
	@Bean
	InitializingBean statelessProtocolGuard(Environment environment) {
		return () -> {
			String protocol = environment.getProperty(PROTOCOL_PROPERTY);
			if (!"STATELESS".equalsIgnoreCase(protocol)) {
				throw new IllegalStateException("Origin validation is wired only for " + PROTOCOL_PROPERTY
						+ "=STATELESS, but the protocol is " + protocol + " (ADR-004)");
			}
		};
	}

}
