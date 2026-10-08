package dev.kintsugi.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The knowledge store as compose builds it, shared by every test class: the pinned pgvector image running the real
 * {@code infra/knowledge-db/init/01-knowledge.sh}, mounted as mcp-tools' {@code KnowledgeStoreRoleTest} does.
 */
final class KnowledgeStoreContainer {

	static final String INDEXER_PASSWORD = "knowledge-indexer-test";

	static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	/** pgvector/pgvector:0.8.7-pg18-trixie, pinned by the index digest recorded in ADR-002. */
	private static final DockerImageName IMAGE = DockerImageName
		.parse("pgvector/pgvector@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0")
		.asCompatibleSubstituteFor("postgres");

	private static PostgreSQLContainer container;

	private KnowledgeStoreContainer() {
	}

	static synchronized PostgreSQLContainer get() {
		if (container == null) {
			Path initScript = REPO.resolve("infra/knowledge-db/init/01-knowledge.sh");
			if (!Files.isRegularFile(initScript)) {
				throw new IllegalStateException("Init script not found at " + initScript);
			}
			container = new PostgreSQLContainer(IMAGE)
				.withCopyFileToContainer(MountableFile.forHostPath(initScript, 0755),
						"/docker-entrypoint-initdb.d/01-knowledge.sh")
				.withEnv("KNOWLEDGE_INDEXER_DB_PASSWORD", INDEXER_PASSWORD)
				.withEnv("KINTSUGI_AGENT_DB_PASSWORD", "kintsugi-agent-test");
			container.start(); // stopped by Testcontainers' reaper when the JVM exits
		}
		return container;
	}

	static String knowledgeUrl() {
		PostgreSQLContainer store = get();
		return "jdbc:postgresql://" + store.getHost() + ":" + store.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
				+ "/knowledge";
	}

	/** The indexer's own connection: knowledge_indexer, as in compose. */
	static DataSource indexerDataSource() {
		return new SimpleDriverDataSource(new org.postgresql.Driver(), knowledgeUrl(), "knowledge_indexer",
				INDEXER_PASSWORD);
	}

	static KnowledgeStore indexerStore() {
		DataSource dataSource = indexerDataSource();
		return new KnowledgeStore(new JdbcTemplate(dataSource),
				new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
	}

	/** The bootstrap superuser, for resetting and inspecting the tables only. */
	static JdbcTemplate admin() {
		PostgreSQLContainer store = get();
		return new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(), knowledgeUrl(),
				store.getUsername(), store.getPassword()));
	}

	static void empty() {
		admin().execute("TRUNCATE runbook_chunks, index_metadata");
	}

	/** Every chunk row, ordered, as text: equal snapshots mean an untouched index. */
	static List<String> snapshot() {
		JdbcTemplate admin = admin();
		List<String> rows = new java.util.ArrayList<>(admin.queryForList(
				"SELECT id || '|' || content || '|' || metadata::text || '|' || embedding::text FROM runbook_chunks"
						+ " ORDER BY id", String.class));
		rows.addAll(admin.queryForList("SELECT model_name || '|' || model_digest || '|' || dimensions || '|'"
				+ " || document_prefix || '|' || query_prefix || '|' || indexed_at FROM index_metadata", String.class));
		return rows;
	}

	static List<Map<String, String>> chunkMetadata() {
		return admin().queryForList("SELECT metadata::text FROM runbook_chunks ORDER BY id", String.class)
			.stream()
			.map(json -> JsonMapper.shared().readValue(json, new TypeReference<Map<String, String>>() {
			}))
			.toList();
	}

}
