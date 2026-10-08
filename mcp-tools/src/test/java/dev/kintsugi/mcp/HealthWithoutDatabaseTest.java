package dev.kintsugi.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mcp-tools stays UP while a database it reads is unreachable: the outage is evidence its tools report as
 * {@code UPSTREAM_ERROR}, not a reason to fail the compose healthcheck. So there is no {@code db} health indicator,
 * neither for the lab pools nor for the knowledge store's pool ({@code management.health.db.enabled=false} covers
 * every {@code DataSource}).
 */
class HealthWithoutDatabaseTest {

	/** A port nothing listens on: it was free a moment ago. */
	static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static void labPostgresUnreachable(DynamicPropertyRegistry registry) {
		int unreachable = closedPort();
		registry.add("kintsugi.mcp.postgres.host", () -> "127.0.0.1");
		registry.add("kintsugi.mcp.postgres.port", () -> unreachable);
		registry.add("kintsugi.mcp.postgres.password", () -> "unused");
	}

	static void assertUp(int port) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
					HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("\"db\"");
	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	class LabPostgresUnreachable {

		@LocalServerPort
		int port;

		@DynamicPropertySource
		static void unreachableDatabase(DynamicPropertyRegistry registry) {
			labPostgresUnreachable(registry);
		}

		@Test
		void healthIsUpWhilePostgresIsUnreachable() throws Exception {
			assertUp(this.port);
		}

	}

	/** The lab database is up here, so only the knowledge store could turn the health DOWN. */
	@Nested
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	class KnowledgeStoreUnreachable {

		@LocalServerPort
		int port;

		@DynamicPropertySource
		static void unreachableStore(DynamicPropertyRegistry registry) {
			int unreachable = closedPort();
			registry.add("kintsugi.mcp.knowledge.host", () -> "127.0.0.1");
			registry.add("kintsugi.mcp.knowledge.port", () -> unreachable);
			registry.add("kintsugi.mcp.knowledge.password", () -> "unused");
		}

		@Test
		void healthIsUpWhileTheKnowledgeStoreIsUnreachable() throws Exception {
			assertUp(this.port);
		}

	}

	/**
	 * The lab database is up here too; the knowledge store's password is empty, and its host is set to a name that
	 * never resolves ({@code .invalid}, RFC 6761), so nothing could connect even if the empty password were tried.
	 */
	@Nested
	@Import(TestcontainersConfiguration.class)
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	class KnowledgePasswordUnset {

		@LocalServerPort
		int port;

		@DynamicPropertySource
		static void unsetPassword(DynamicPropertyRegistry registry) {
			registry.add("kintsugi.mcp.knowledge.host", () -> "knowledge-db.invalid");
			registry.add("kintsugi.mcp.knowledge.password", () -> "");
		}

		@Test
		void healthIsUpWithoutTheKnowledgePassword() throws Exception {
			assertUp(this.port);
		}

	}

}
