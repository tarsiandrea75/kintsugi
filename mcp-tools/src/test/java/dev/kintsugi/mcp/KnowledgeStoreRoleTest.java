package dev.kintsugi.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The knowledge store as compose builds it: the pinned pgvector image runs the real
 * {@code infra/knowledge-db/init/01-knowledge.sh} (knowledge-base spec; ADR-002). The schema it creates is shared by
 * the indexer and by {@code search_runbooks}, so its roles and grants are tested here, once.
 */
class KnowledgeStoreRoleTest {

	private static final String PERMISSION_DENIED = "42501";

	private static final String READ_ONLY_TRANSACTION = "25006";

	private static final String INDEXER_PASSWORD = "knowledge-indexer-test";

	private static final String AGENT_PASSWORD = "kintsugi-agent-knowledge-test";

	private static final int DIMENSIONS = 768;

	private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	/** pgvector/pgvector:0.8.7-pg18-trixie, pinned by the index digest recorded in ADR-002. */
	private static final DockerImageName IMAGE = DockerImageName
		.parse("pgvector/pgvector@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0")
		.asCompatibleSubstituteFor("postgres");

	private static PostgreSQLContainer container;

	@BeforeAll
	static void start() {
		Path initScript = REPO.resolve("infra/knowledge-db/init/01-knowledge.sh");
		if (!Files.isRegularFile(initScript)) {
			throw new IllegalStateException("Init script not found at " + initScript);
		}
		container = new PostgreSQLContainer(IMAGE)
			.withCopyFileToContainer(MountableFile.forHostPath(initScript, 0755),
					"/docker-entrypoint-initdb.d/01-knowledge.sh")
			.withEnv("KNOWLEDGE_INDEXER_DB_PASSWORD", INDEXER_PASSWORD)
			.withEnv("KINTSUGI_AGENT_DB_PASSWORD", AGENT_PASSWORD);
		container.start();
	}

	@AfterAll
	static void stop() {
		if (container != null) {
			container.stop();
		}
	}

	@Test
	void theVectorExtensionIsInstalledInKnowledge() throws SQLException {
		try (Connection connection = asAgent(); Statement sql = connection.createStatement();
				ResultSet rows = sql.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).isEqualTo("0.8.7");
		}
	}

	@Test
	void theStoreHoldsNoFaultsDatabase() throws SQLException {
		try (Connection connection = asAgent(); Statement sql = connection.createStatement();
				ResultSet rows = sql.executeQuery("SELECT datname FROM pg_database")) {
			List<String> names = new ArrayList<>();
			while (rows.next()) {
				names.add(rows.getString(1));
			}
			assertThat(names).contains("knowledge").doesNotContain("faults");
		}
	}

	@Test
	void theIndexerWritesChunksAndMetadata() throws SQLException {
		try (Connection connection = asIndexer(); Statement sql = connection.createStatement()) {
			sql.execute("INSERT INTO runbook_chunks (content, metadata, embedding) VALUES ('Key signals',"
					+ " '{\"service\":\"order-service\",\"section\":\"Key signals\","
					+ "\"source\":\"docs/runbooks/order-service.md\",\"content_hash\":\"abc\"}', '" + vector(1) + "')");
			sql.execute("INSERT INTO index_metadata (model_name, model_digest, dimensions, document_prefix, query_prefix)"
					+ " VALUES ('nomic-embed-text', 'd1', 768, 'search_document: ', 'search_query: ')"
					+ " ON CONFLICT (id) DO NOTHING");
			assertThat(sql.executeUpdate("UPDATE index_metadata SET model_digest = 'd2', indexed_at = now()"))
				.isEqualTo(1);
		}
	}

	@Test
	void aSecondMetadataRowIsRejected() throws SQLException {
		try (Connection connection = asIndexer(); Statement sql = connection.createStatement()) {
			assertThatExceptionOfType(SQLException.class)
				.isThrownBy(() -> sql.execute("INSERT INTO index_metadata (id, model_name, model_digest, dimensions,"
						+ " document_prefix, query_prefix) VALUES (2, 'm', 'd', 768, '', '')"));
		}
	}

	@Test
	void aVectorOfTheWrongDimensionIsRejected() throws SQLException {
		try (Connection connection = asIndexer(); Statement sql = connection.createStatement()) {
			assertThatExceptionOfType(SQLException.class)
				.isThrownBy(() -> sql.execute(
						"INSERT INTO runbook_chunks (content, metadata, embedding) VALUES ('x', '{}', '[1,2,3]')"))
				.withMessageContaining("768");
		}
	}

	@Test
	void theAgentReadsBothTablesAndTheCosineSearchUsesTheHnswIndex() throws SQLException {
		try (Connection indexer = asIndexer(); Statement write = indexer.createStatement()) {
			for (int i = 0; i < 20; i++) {
				write.execute("INSERT INTO runbook_chunks (content, metadata, embedding) VALUES ('chunk " + i
						+ "', '{}', '" + vector(i + 2) + "')");
			}
		}
		try (Connection connection = asAgent(); Statement sql = connection.createStatement()) {
			try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM index_metadata")) {
				assertThat(rows.next()).isTrue();
			}
			try (ResultSet rows = sql.executeQuery("SELECT content, 1 - (embedding <=> '" + vector(3)
					+ "') AS score FROM runbook_chunks ORDER BY embedding <=> '" + vector(3) + "' LIMIT 5")) {
				assertThat(rows.next()).isTrue();
			}
			sql.execute("SET enable_seqscan = off");
			StringJoiner plan = new StringJoiner("\n");
			try (ResultSet rows = sql.executeQuery("EXPLAIN SELECT id FROM runbook_chunks ORDER BY embedding <=> '"
					+ vector(3) + "' LIMIT 5")) {
				while (rows.next()) {
					plan.add(rows.getString(1));
				}
			}
			assertThat(plan.toString()).contains("Index Scan").contains("runbook_chunks_embedding_hnsw");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "INSERT INTO runbook_chunks (content, metadata, embedding) VALUES ('x', '{}', NULL)",
			"UPDATE runbook_chunks SET content = 'x'", "DELETE FROM runbook_chunks", "TRUNCATE runbook_chunks",
			"UPDATE index_metadata SET model_digest = 'x'", "DELETE FROM index_metadata",
			"CREATE TABLE notes (id int)", "DROP INDEX runbook_chunks_embedding_hnsw" })
	void theAgentCannotWriteEvenAfterLeavingReadOnlyMode(String statement) throws SQLException {
		try (Connection connection = asAgent(); Statement sql = connection.createStatement()) {
			// The session default stops the statement first...
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> sql.execute(statement))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(READ_ONLY_TRANSACTION));
			// ...and the missing privilege stops it when the session turns that default off.
			sql.execute("SET default_transaction_read_only = off");
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> sql.execute(statement))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED));
		}
	}

	@Test
	void neitherRoleIsPrivileged() throws SQLException {
		try (Connection connection = asAgent(); Statement sql = connection.createStatement();
				ResultSet rows = sql.executeQuery("SELECT rolname, rolsuper, rolcreatedb, rolcreaterole,"
						+ " rolreplication, rolbypassrls FROM pg_roles"
						+ " WHERE rolname IN ('knowledge_indexer', 'kintsugi_agent')")) {
			int seen = 0;
			while (rows.next()) {
				seen++;
				for (int column = 2; column <= 6; column++) {
					assertThat(rows.getBoolean(column))
						.as(rows.getString(1) + " " + rows.getMetaData().getColumnName(column))
						.isFalse();
				}
			}
			assertThat(seen).isEqualTo(2);
		}
	}

	@Test
	void theIndexerOwnsTheTables() throws SQLException {
		try (Connection connection = asAgent(); Statement sql = connection.createStatement();
				ResultSet rows = sql.executeQuery("SELECT DISTINCT tableowner FROM pg_tables"
						+ " WHERE tablename IN ('runbook_chunks', 'index_metadata')")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).isEqualTo("knowledge_indexer");
			assertThat(rows.next()).isFalse();
		}
	}

	@Test
	void agentSessionsStartWithTheRoleSettings() throws SQLException {
		try (Connection connection = asAgent()) {
			assertThat(show(connection, "default_transaction_read_only")).isEqualTo("on");
			assertThat(show(connection, "statement_timeout")).isEqualTo("3s");
			assertThat(show(connection, "transaction_timeout")).isEqualTo("5s");
			assertThat(show(connection, "idle_in_transaction_session_timeout")).isEqualTo("5s");
		}
	}

	@Test
	void aRoleWithoutAGrantCannotConnect() throws SQLException {
		try (Connection admin = DriverManager.getConnection(url("postgres"), container.getUsername(),
				container.getPassword()); Statement sql = admin.createStatement()) {
			sql.execute("CREATE ROLE stranger LOGIN PASSWORD 'stranger'");
		}
		assertThatExceptionOfType(SQLException.class)
			.isThrownBy(() -> DriverManager.getConnection(url("knowledge"), "stranger", "stranger").close())
			.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED))
			.withMessageContaining("CONNECT");
	}

	private static Connection asAgent() throws SQLException {
		return DriverManager.getConnection(url("knowledge"), "kintsugi_agent", AGENT_PASSWORD);
	}

	private static Connection asIndexer() throws SQLException {
		return DriverManager.getConnection(url("knowledge"), "knowledge_indexer", INDEXER_PASSWORD);
	}

	private static String url(String database) {
		return "jdbc:postgresql://" + container.getHost() + ":"
				+ container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
	}

	/** A 768-dimension vector literal, distinct per seed. */
	private static String vector(int seed) {
		List<String> values = new ArrayList<>(Collections.nCopies(DIMENSIONS, "0"));
		values.set(seed % DIMENSIONS, "1");
		values.set((seed * 7 + 1) % DIMENSIONS, "0.5");
		return "[" + String.join(",", values) + "]";
	}

	private static String show(Connection connection, String setting) throws SQLException {
		try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SHOW " + setting)) {
			rows.next();
			return rows.getString(1);
		}
	}

}
