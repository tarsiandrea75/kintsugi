package dev.kintsugi.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application as {@code main} runs it: a one-shot job whose exit code is the run's outcome, connected to the store
 * as {@code knowledge_indexer}.
 */
class KnowledgeApplicationTest {

	private static FakeOllama ollama;

	@TempDir
	Path runbooks;

	@BeforeAll
	static void start() {
		ollama = FakeOllama.start();
	}

	@AfterAll
	static void stop() {
		ollama.close();
	}

	@BeforeEach
	void reset() throws IOException {
		KnowledgeStoreContainer.empty();
		ollama.reset();
		Files.writeString(this.runbooks.resolve("order-service.md"),
				"# Runbook: order-service\n\n## Key signals\n\nRead the order status counts.\n");
	}

	@Test
	void aCleanRunExitsWithZeroAndConnectsAsTheUnprivilegedIndexer() {
		try (ConfigurableApplicationContext context = run()) {
			assertThat(new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
				.queryForMap("SELECT current_user AS name, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles"
						+ " WHERE rolname = current_user"))
				.isEqualTo(Map.of("name", "knowledge_indexer", "rolsuper", false, "rolcreatedb", false,
						"rolcreaterole", false));
			// What main hands to System.exit; it also closes the context.
			assertThat(SpringApplication.exit(context)).isZero();
		}
		assertThat(KnowledgeStoreContainer.chunkMetadata()).hasSize(1);
	}

	@Test
	void aFailedRunExitsWithANonZeroCode() throws IOException {
		Files.writeString(this.runbooks.resolve("gateway-mock.md"), "# Runbook\n\n## Notes\n\nThis is fault F3\n");

		try (ConfigurableApplicationContext context = run()) {
			assertThat(SpringApplication.exit(context)).isEqualTo(1);
		}
		assertThat(ollama.embedCalls()).isEmpty();
		assertThat(KnowledgeStoreContainer.snapshot()).isEmpty();
	}

	/** Command-line arguments, as compose's environment would, so they override application.properties. */
	private ConfigurableApplicationContext run() {
		return new SpringApplicationBuilder(KnowledgeApplication.class).run(
				"--kintsugi.knowledge.runbooks-dir=" + this.runbooks, "--spring.ai.ollama.base-url=" + ollama.baseUrl(),
				"--spring.datasource.url=" + KnowledgeStoreContainer.knowledgeUrl(),
				"--spring.datasource.password=" + KnowledgeStoreContainer.INDEXER_PASSWORD);
	}

}
