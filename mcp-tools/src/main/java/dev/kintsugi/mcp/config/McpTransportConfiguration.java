package dev.kintsugi.mcp.config;

import java.util.List;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Replaces Spring AI's stateless WebMVC transport to turn on Origin and Host validation (ADR-004).
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

	/**
	 * The {@code Host} values {@code /mcp} accepts, comma-separated; any other answers 421. In compose:
	 * {@code mcp-tools:8085} for the agent on the compose network, {@code localhost:18085} and
	 * {@code 127.0.0.1:18085} for the host. A second layer against DNS rebinding, next to the Origin check. An entry
	 * ending in {@code :*} accepts any port. There is no default in code and an empty list stops the startup, because
	 * the SDK skips the check altogether when the list is empty.
	 */
	static final String ALLOWED_HOSTS_PROPERTY = "kintsugi.mcp.allowed-hosts";

	@Bean
	@ConditionalOnProperty(name = PROTOCOL_PROPERTY, havingValue = "STATELESS")
	WebMvcStatelessServerTransport webMvcStatelessServerTransport(@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties serverProperties, Environment environment) {
		return WebMvcStatelessServerTransport.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.messageEndpoint(serverProperties.getMcpEndpoint())
			.securityValidator(securityValidator(allowedHosts(environment)))
			.build();
	}

	/** Rejects any {@code Origin} header (403) and any {@code Host} outside {@code allowedHosts} (421). */
	static ServerTransportSecurityValidator securityValidator(List<String> allowedHosts) {
		return DefaultServerTransportSecurityValidator.builder()
			.allowedOrigins(ALLOWED_ORIGINS)
			.allowedHosts(allowedHosts)
			.build();
	}

	static List<String> allowedHosts(Environment environment) {
		List<String> hosts = Binder.get(environment)
			.bind(ALLOWED_HOSTS_PROPERTY, Bindable.listOf(String.class))
			.orElse(List.of())
			.stream()
			.map(String::strip)
			.filter(host -> !host.isEmpty())
			.toList();
		if (hosts.isEmpty()) {
			throw new IllegalStateException(ALLOWED_HOSTS_PROPERTY
					+ " must list the Host values /mcp accepts; an empty list would turn the Host check off (ADR-004)");
		}
		return hosts;
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
