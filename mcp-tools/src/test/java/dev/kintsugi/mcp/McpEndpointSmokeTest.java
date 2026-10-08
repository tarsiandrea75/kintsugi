package dev.kintsugi.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts and answers MCP JSON-RPC over Streamable HTTP on {@code POST /mcp}, as a real client would
 * call it.
 */
@Import(TestcontainersConfiguration.class)
// The test server listens on a random port, so its Host header is localhost:<port>.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "kintsugi.mcp.allowed-hosts=localhost:*")
class McpEndpointSmokeTest {

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
			"capabilities":{},"clientInfo":{"name":"smoke-test","version":"1"}}}""";

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Autowired
	ConfigurableApplicationContext context;

	@Test
	void initializeAnswersWithServerInfoAndProtocolVersion() throws Exception {
		// Set by Surefire from the pom (see pom.xml), so the check follows any version bump.
		String pomVersion = System.getProperty("mcp-tools.pom-version");
		assertThat(pomVersion).as("mcp-tools.pom-version system property").isNotBlank();

		HttpResponse<String> response = post(INITIALIZE, null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"protocolVersion\":\"2025-11-25\"")
			.contains("\"name\":\"mcp-tools\"")
			.contains("\"version\":\"" + pomVersion + "\"")
			.doesNotContain("@project.version@")
			.contains("\"tools\"");
	}

	@Test
	void toolsListAnswersWithoutASession() throws Exception {
		HttpResponse<String> response = post(TOOLS_LIST, null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"id\":2").contains("\"tools\":[");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://evil.example", "http://localhost:18085", "null" })
	void requestWithAnyOriginIsRejected(String origin) throws Exception {
		HttpResponse<String> response = post(INITIALIZE, origin);

		assertThat(response.statusCode()).isEqualTo(403);
	}

	@Test
	void getIsNotAllowedOnTheStatelessEndpoint() throws Exception {
		HttpResponse<String> response = this.http.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/mcp"))
					.header("Accept", "text/event-stream")
					.GET()
					.build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(405);
	}

	@Test
	void theActiveTransportIsTheOneWithOriginValidation() {
		BeanDefinition transport = this.context.getBeanFactory().getBeanDefinition("webMvcStatelessServerTransport");

		assertThat(transport.getFactoryBeanName()).isEqualTo("mcpTransportConfiguration");
		assertThat(this.context.getBeanNamesForType(WebMvcStatelessServerTransport.class))
			.containsExactly("webMvcStatelessServerTransport");
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
