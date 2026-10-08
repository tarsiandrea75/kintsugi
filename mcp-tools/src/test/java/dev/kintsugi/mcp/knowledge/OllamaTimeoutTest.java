package dev.kintsugi.mcp.knowledge;

import java.time.Duration;

import dev.kintsugi.mcp.KnowledgeDatabase;
import dev.kintsugi.mcp.TestcontainersConfiguration;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ollama client {@code search_runbooks} uses is the one Spring AI's auto-configuration builds, and it honours the
 * 5 s read timeout of {@code spring.http.clients.read-timeout} in {@code application.properties}: an Ollama that
 * accepts the request and never answers ends the call with {@code UPSTREAM_ERROR} in about 5 s, for the digest
 * lookup ({@code GET /api/tags}) and for the query embedding ({@code POST /api/embed}) alike. Nothing here sets a
 * timeout, so the production values are the ones measured.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OllamaTimeoutTest {

	/** {@code spring.http.clients.read-timeout} in {@code application.properties}. */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	/** Room for the knowledge store query, the HTTP client and a loaded build machine. */
	private static final Duration MARGIN = Duration.ofSeconds(4);

	private static final FakeOllama OLLAMA = FakeOllama.start();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	RunbookTools tools;

	@DynamicPropertySource
	static void stores(DynamicPropertyRegistry registry) {
		registry.add("spring.ai.ollama.base-url", OLLAMA::baseUrl);
		registry.add("kintsugi.mcp.knowledge.host", KnowledgeDatabase::host);
		registry.add("kintsugi.mcp.knowledge.port", KnowledgeDatabase::port);
		registry.add("kintsugi.mcp.knowledge.password", () -> KnowledgeDatabase.AGENT_PASSWORD);
	}

	@AfterAll
	static void stop() {
		OLLAMA.close();
	}

	@BeforeEach
	void index() {
		OLLAMA.reset();
		KnowledgeDatabase.empty();
		KnowledgeDatabase.builtWith("nomic-embed-text", FakeOllama.DIGEST, 768, "search_document: ", "search_query: ");
	}

	/** First, so the context's digest cache is still empty and the search must ask {@code /api/tags}. */
	@Test
	@Order(1)
	void aDigestLookupThatNeverAnswersTimesOutAfterTheReadTimeout() {
		OLLAMA.stallsOn("/api/tags");

		long started = System.nanoTime();
		JsonNode body = search();
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		assertThat(body.path("outcome").asString()).isEqualTo("UPSTREAM_ERROR");
		assertThat(body.path("reason").asString()).isEqualTo("Ollama did not answer in time");
		assertThat(OLLAMA.tagCalls()).isEqualTo(1);
		assertThat(OLLAMA.embeddedInputs()).isEmpty();
		assertThat(took).isGreaterThanOrEqualTo(READ_TIMEOUT.minusMillis(500)).isLessThan(READ_TIMEOUT.plus(MARGIN));
	}

	@Test
	@Order(2)
	void aQueryEmbeddingThatNeverAnswersTimesOutAfterTheReadTimeoutWithoutARetry() {
		OLLAMA.stallsOn("/api/embed");

		long started = System.nanoTime();
		JsonNode body = search();
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		assertThat(body.path("outcome").asString()).isEqualTo("UPSTREAM_ERROR");
		assertThat(body.path("reason").asString()).isEqualTo("Ollama did not answer in time");
		assertThat(OLLAMA.embeddedInputs()).containsExactly("search_query: payments time out");
		assertThat(took).isGreaterThanOrEqualTo(READ_TIMEOUT.minusMillis(500)).isLessThan(READ_TIMEOUT.plus(MARGIN));
	}

	private JsonNode search() {
		return JSON.readTree(((TextContent) this.tools.searchRunbooks("payments time out", null, null).content().get(0))
			.text());
	}

}
