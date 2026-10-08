package dev.kintsugi.agent.lab;

import static dev.kintsugi.agent.lab.FakeMcpServer.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link McpLabTools} against a fake mcp-tools speaking Streamable HTTP (agent spec, "Tool calls run small and
 * recorded", "Phases and their tools").
 */
class McpLabToolsTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private FakeMcpServer server;

	private McpSyncClient client;

	@AfterEach
	void close() {
		if (this.client != null) {
			this.client.close();
		}
		if (this.server != null) {
			this.server.close();
		}
	}

	@Test
	void eachPhaseOffersExactlyItsTools() throws IOException {
		LabTools tools = connected();

		assertThat(names(tools.toolsFor(Phase.TRIAGE))).containsExactly("status_summary", "query_metric",
				"service_restarts");
		assertThat(names(tools.toolsFor(Phase.INVESTIGATE))).containsExactly("search_logs", "count_logs",
				"recent_events", "get_order_trace");
		assertThat(names(tools.toolsFor(Phase.KNOWLEDGE))).containsExactly("search_runbooks");
		assertThat(tools.toolsFor(Phase.CONCLUDE)).isEmpty();
	}

	@Test
	void anOfferedToolCarriesItsSchemaButCannotBeExecuted() throws IOException {
		ToolCallback searchLogs = connected().toolsFor(Phase.INVESTIGATE).get(0);

		assertThat(searchLogs.getToolDefinition().inputSchema()).contains("\"service\"").contains("\"limit\"");
		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> searchLogs.call("{\"service\":\"order-service\",\"sinceMinutes\":5}"));
		assertThat(this.server.calls()).isEmpty();
	}

	@Test
	void theConcludePhaseNeedsNoConnection() {
		this.client = client("http://127.0.0.1:" + closedPort());
		LabTools tools = new McpLabTools(this.client, SyncMcpToolCallbackProvider.builder().mcpClients(this.client).build());

		assertThat(tools.toolsFor(Phase.CONCLUDE)).isEmpty();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			search_logs     | {"service":"order-service","sinceMinutes":15,"limit":100} | 20
			search_logs     | {"service":"order-service","sinceMinutes":15}             | 20
			search_logs     | {"service":"order-service","sinceMinutes":15,"limit":5}   | 5
			search_logs     | {"service":"order-service","limit":99999999999999999999}  | 20
			recent_events   | {"source":"order","sinceMinutes":15}                      | 20
			recent_events   | {"source":"order","sinceMinutes":15,"limit":21}           | 20
			search_runbooks | {"query":"payments time out","limit":10}                  | 3
			search_runbooks | {"query":"payments time out"}                             | 3
			search_runbooks | {"query":"payments time out","limit":2}                   | 2
			""")
	void theLimitIsCappedBeforeTheCallAndRecordedAsSent(String tool, String args, int sent) throws IOException {
		ToolResult result = connected().call(tool, args);

		JsonNode received = this.server.calls().get(0).path("arguments");
		assertThat(received.path("limit").asInt()).isEqualTo(sent);
		assertThat(JSON.readTree(result.args())).isEqualTo(received);
	}

	@Test
	void aLimitThatIsNotAnIntegerIsLeftForMcpToolsToReject() throws IOException {
		connected().call("search_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15,\"limit\":\"many\"}");

		assertThat(this.server.calls().get(0).path("arguments").path("limit").asString()).isEqualTo("many");
	}

	@Test
	void aToolWithoutALimitGetsNone() throws IOException {
		connected().call("count_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}");

		assertThat(this.server.calls().get(0).path("arguments").has("limit")).isFalse();
	}

	@Test
	void theOutputIsReturnedVerbatimWithItsOutcome() throws IOException {
		LabTools tools = connected();
		this.server.answer("search_logs", fixture("search_logs-payment-warn.json"));

		ToolResult result = tools.call("search_logs", "{\"service\":\"payment-service\",\"sinceMinutes\":360}");

		assertThat(result.tool()).isEqualTo("search_logs");
		assertThat(result.outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(result.rawOutput()).isEqualTo(fixture("search_logs-payment-warn.json"));
		assertThat(result.durationMs()).isNotNegative();
	}

	@Test
	void aToolErrorKeepsItsFullOutput() throws IOException {
		LabTools tools = connected();
		this.server.answer("search_logs", fixture("search_logs-invalid.json"));

		ToolResult result = tools.call("search_logs", "{\"service\":\"fault-injector\",\"sinceMinutes\":15}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.INVALID_PARAMETER);
		assertThat(result.rawOutput()).isEqualTo(fixture("search_logs-invalid.json"));
	}

	@Test
	void anUpstreamErrorIsReported() throws IOException {
		String output = "{\"outcome\":\"UPSTREAM_ERROR\",\"reason\":\"PostgreSQL orders is unreachable\",\"returned\":0,"
				+ "\"truncated\":false,\"items\":[]}";
		LabTools tools = connected();
		this.server.answer("status_summary", output);

		ToolResult result = tools.call("status_summary", "{\"table\":\"orders\"}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.UPSTREAM_ERROR);
		assertThat(result.rawOutput()).isEqualTo(output);
	}

	@Test
	void aTruncatedOutputKeepsItsOutcome() throws IOException {
		String output = "{\"outcome\":\"OK\",\"returned\":1,\"truncated\":true,\"items\":[{\"service\":\"order-service\"}]}"
				+ "\n[truncated]";
		LabTools tools = connected();
		this.server.answer("search_logs", output);

		ToolResult result = tools.call("search_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(result.rawOutput()).isEqualTo(output);
	}

	@Test
	void anUnreadableResultIsAClientError() throws IOException {
		LabTools tools = connected();
		this.server.answer("count_logs", "not json");

		ToolResult result = tools.call("count_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(result.rawOutput()).startsWith("mcp-tools call failed:").contains("not JSON");
	}

	@Test
	void aClosedPortIsAClientErrorAndAnUnavailableToolList() {
		this.client = client("http://127.0.0.1:" + closedPort());
		LabTools tools = new McpLabTools(this.client, SyncMcpToolCallbackProvider.builder().mcpClients(this.client).build());

		ToolResult result = tools.call("count_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(result.rawOutput()).startsWith("mcp-tools call failed:");
		assertThat(result.args()).isEqualTo("{\"service\":\"order-service\",\"sinceMinutes\":15}");
		assertThatExceptionOfType(LabToolsUnavailableException.class).isThrownBy(() -> tools.toolsFor(Phase.TRIAGE));
	}

	@Test
	void aClientThatFailedToConnectConnectsOnceMcpToolsIsUp() throws IOException {
		int port = closedPort();
		this.client = client("http://127.0.0.1:" + port);
		LabTools tools = new McpLabTools(this.client, SyncMcpToolCallbackProvider.builder().mcpClients(this.client).build());
		String args = "{\"service\":\"order-service\",\"sinceMinutes\":15}";
		assertThat(tools.call("count_logs", args).outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);

		this.server = FakeMcpServer.startOn(port).answer("count_logs", fixture("count_logs.json"));

		assertThat(tools.call("count_logs", args).outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(names(tools.toolsFor(Phase.KNOWLEDGE))).containsExactly("search_runbooks");
	}

	@Test
	void argumentsThatAreNotAnObjectAreRejectedWithoutACall() throws IOException {
		ToolResult result = connected().call("count_logs", "[1, 2]");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.INVALID_PARAMETER);
		assertThat(JSON.readTree(result.args()).asString()).isEqualTo("[1, 2]");
		assertThat(this.server.calls()).isEmpty();
	}

	@Test
	void blankArgumentsAreAnEmptyObject() throws IOException {
		connected().call("service_restarts", " ");

		assertThat(this.server.calls().get(0).path("arguments").isEmpty()).isTrue();
	}

	@Test
	void aNameOutsideTheLabToolsIsABugOfTheCaller() throws IOException {
		LabTools tools = connected();

		assertThatIllegalArgumentException().isThrownBy(() -> tools.call("rollback", "{}"));
		assertThat(this.server.calls()).isEmpty();
	}

	private LabTools connected() throws IOException {
		this.server = FakeMcpServer.start();
		this.client = client(this.server.url());
		return new McpLabTools(this.client, SyncMcpToolCallbackProvider.builder().mcpClients(this.client).build());
	}

	private static McpSyncClient client(String url) {
		return McpClient.sync(HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(5))
			.initializationTimeout(Duration.ofSeconds(5))
			.build();
	}

	/** A port nothing listens on: bound once by the system, then released. */
	static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<String> names(List<ToolCallback> tools) {
		return tools.stream().map(tool -> tool.getToolDefinition().name()).toList();
	}

}
