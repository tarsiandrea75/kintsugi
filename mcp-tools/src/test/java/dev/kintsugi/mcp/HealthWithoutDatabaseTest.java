package dev.kintsugi.mcp;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mcp-tools stays UP while PostgreSQL is unreachable: the database outage is evidence its tools report as
 * {@code UPSTREAM_ERROR}, not a reason to fail the compose healthcheck. So there is no {@code db} health indicator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthWithoutDatabaseTest {

	@LocalServerPort
	int port;

	@DynamicPropertySource
	static void unreachableDatabase(DynamicPropertyRegistry registry) throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		int unreachable = closedPort;
		registry.add("kintsugi.mcp.postgres.host", () -> "127.0.0.1");
		registry.add("kintsugi.mcp.postgres.port", () -> unreachable);
		registry.add("kintsugi.mcp.postgres.password", () -> "unused");
	}

	@Test
	void healthIsUpWhilePostgresIsUnreachable() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/actuator/health")).build(),
					HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("\"db\"");
	}

}
