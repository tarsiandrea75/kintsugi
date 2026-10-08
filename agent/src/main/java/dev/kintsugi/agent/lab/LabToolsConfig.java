package dev.kintsugi.agent.lab;

import java.util.List;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link LabTools} to the MCP client that Spring AI's starter builds from
 * {@code spring.ai.mcp.client.streamable-http.connections}. Nothing here talks to mcp-tools: the client is created
 * uninitialized ({@code spring.ai.mcp.client.initialized=false}) and connects on its first request, so the agent
 * starts while mcp-tools is down.
 */
@Configuration(proxyBeanMethods = false)
public class LabToolsConfig {

	@Bean
	LabTools labTools(List<McpSyncClient> mcpSyncClients, SyncMcpToolCallbackProvider definitions) {
		if (mcpSyncClients.size() != 1) {
			throw new IllegalStateException("the agent needs exactly one MCP connection, to mcp-tools; found "
					+ mcpSyncClients.size());
		}
		return new McpLabTools(mcpSyncClients.get(0), definitions);
	}

}
