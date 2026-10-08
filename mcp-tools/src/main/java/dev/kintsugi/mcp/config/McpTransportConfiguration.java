package dev.kintsugi.mcp.config;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces Spring AI's stateless WebMVC transport to turn on Origin validation (ADR-004).
 *
 * <p>
 * Spring AI 2.0.1 builds the transport with the SDK's no-op security validator and exposes no property to change
 * it. This bean has the same name, so the auto-configuration backs off ({@code @ConditionalOnMissingBean}). The
 * allow-list of origins is empty: the only client is the agent, a server process that sends no {@code Origin}
 * header, so a request that carries one comes from a browser and is rejected with 403. That blocks DNS-rebinding
 * attacks from a page open in the developer's browser.
 */
@Configuration(proxyBeanMethods = false)
public class McpTransportConfiguration {

	@Bean
	WebMvcStatelessServerTransport webMvcStatelessServerTransport(@Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
			McpServerStreamableHttpProperties serverProperties) {
		return WebMvcStatelessServerTransport.builder()
			.jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
			.messageEndpoint(serverProperties.getMcpEndpoint())
			.securityValidator(DefaultServerTransportSecurityValidator.builder().build())
			.build();
	}

}
