package dev.kintsugi.agent.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.kintsugi.agent.AgentDatabase;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The agent starts while mcp-tools is down: the MCP client is created uninitialized, and a call while mcp-tools is
 * unreachable is a {@code CLIENT_ERROR}, not a failed start (agent spec, "MCP client").
 */
@SpringBootTest
class AgentStartsWithoutMcpToolsTest {

	@Autowired
	LabTools labTools;

	@Autowired
	List<McpSyncClient> clients;

	@Autowired
	McpClientCommonProperties common;

	@Autowired
	McpStreamableHttpClientProperties streamableHttp;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
		registry.add("KINTSUGI_MCP_URL", () -> "http://127.0.0.1:" + McpLabToolsTest.closedPort());
	}

	@Test
	void theClientIsOneLazySyncConnectionToMcpTools() {
		assertThat(this.common.getType()).isEqualTo(McpClientCommonProperties.ClientType.SYNC);
		assertThat(this.common.isInitialized()).isFalse();
		assertThat(this.streamableHttp.getConnections()).containsOnlyKeys("kintsugi");
		assertThat(this.streamableHttp.getConnections().get("kintsugi").endpoint()).isEqualTo("/mcp");
		assertThat(this.clients).hasSize(1);
		assertThat(this.clients.get(0).isInitialized()).isFalse();
	}

	@Test
	void aCallWhileMcpToolsIsDownIsAClientError() {
		ToolResult result = this.labTools.call("count_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(result.rawOutput()).startsWith("mcp-tools call failed:");
	}

}
