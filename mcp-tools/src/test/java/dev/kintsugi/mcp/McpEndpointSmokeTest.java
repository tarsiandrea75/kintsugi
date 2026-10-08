package dev.kintsugi.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts and answers MCP JSON-RPC over Streamable HTTP on {@code POST /mcp}, as a real client would
 * call it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointSmokeTest {

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
			"capabilities":{},"clientInfo":{"name":"smoke-test","version":"1"}}}""";

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Test
	void initializeAnswersWithServerInfoAndProtocolVersion() throws Exception {
		HttpResponse<String> response = post(INITIALIZE, null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"protocolVersion\":\"2025-11-25\"")
			.contains("\"name\":\"mcp-tools\"")
			.contains("\"tools\"");
	}

	@Test
	void toolsListAnswersWithoutASession() throws Exception {
		HttpResponse<String> response = post(TOOLS_LIST, null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"id\":2").contains("\"tools\":[");
	}

	@Test
	void requestWithABrowserOriginIsRejected() throws Exception {
		HttpResponse<String> response = post(INITIALIZE, "http://evil.example");

		assertThat(response.statusCode()).isEqualTo(403);
	}

	private HttpResponse<String> post(String body, String origin) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (origin != null) {
			request.header("Origin", origin);
		}
		return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
