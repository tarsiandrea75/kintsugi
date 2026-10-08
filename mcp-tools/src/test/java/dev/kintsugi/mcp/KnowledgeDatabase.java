package dev.kintsugi.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The knowledge store as compose builds it, shared by the search tests in the JVM: the pinned pgvector image running
 * the real {@code infra/knowledge-db/init/01-knowledge.sh}. Tests seed it as {@code knowledge_indexer}, the role the
 * indexer writes with, with known vectors in place of real embeddings. {@link KnowledgeStoreRoleTest} keeps a container
 * of its own, since it changes grants and roles.
 */
public final class KnowledgeDatabase {

	public static final String AGENT_PASSWORD = "kintsugi-agent-knowledge-test";

	public static final String INDEXER_PASSWORD = "knowledge-indexer-test";

	public static final int DIMENSIONS = 768;

	private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	/** pgvector/pgvector:0.8.7-pg18-trixie, pinned by the index digest recorded in ADR-002. */
	private static final DockerImageName IMAGE = DockerImageName
		.parse("pgvector/pgvector@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0")
		.asCompatibleSubstituteFor("postgres");

	private static final PostgreSQLContainer CONTAINER = start();

	private KnowledgeDatabase() {
	}

	public static String host() {
		return CONTAINER.getHost();
	}

	public static int port() {
		return CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
	}

	/** The indexer's role, to seed chunks and the metadata row as the real indexer would. */
	public static JdbcTemplate asIndexer() {
		return new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
				"jdbc:postgresql://" + host() + ":" + port() + "/knowledge", "knowledge_indexer", INDEXER_PASSWORD));
	}

	/** Empties the index: no chunk, no file hash, no metadata row. */
	public static void empty() {
		asIndexer().execute("DELETE FROM runbook_chunks; DELETE FROM index_metadata; DELETE FROM indexed_files");
	}

	/** Records the model the index was "built" with. */
	public static void builtWith(String model, String digest, int dimensions, String documentPrefix,
			String queryPrefix) {
		asIndexer().update("INSERT INTO index_metadata (id, model_name, model_digest, dimensions, document_prefix,"
				+ " query_prefix) VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET model_name ="
				+ " excluded.model_name, model_digest = excluded.model_digest, dimensions = excluded.dimensions,"
				+ " document_prefix = excluded.document_prefix, query_prefix = excluded.query_prefix", model, digest,
				dimensions, documentPrefix, queryPrefix);
	}

	/** Inserts one chunk; {@code service} {@code null} stands for a README chunk, which has no service. */
	public static void chunk(String service, String section, String content, float[] embedding) {
		String metadata = (service == null ? "{" : "{\"service\":\"" + service + "\",") + "\"section\":\"" + section
				+ "\",\"source\":\"docs/runbooks/" + (service == null ? "README" : service)
				+ ".md\",\"content_hash\":\"h\"}";
		asIndexer().update("INSERT INTO runbook_chunks (content, metadata, embedding) VALUES (?, ?::json, ?::vector)",
				content, metadata, literal(embedding));
	}

	/** A unit vector along {@code axis}. */
	public static float[] axis(int axis) {
		float[] vector = new float[DIMENSIONS];
		vector[axis] = 1;
		return vector;
	}

	/** {@code cos} along axis 0 and {@code sin} along axis 1: its cosine similarity to {@code axis(0)} is {@code cos}. */
	public static float[] towardAxis0(double cos) {
		float[] vector = new float[DIMENSIONS];
		vector[0] = (float) cos;
		vector[1] = (float) Math.sqrt(1 - cos * cos);
		return vector;
	}

	private static String literal(float[] values) {
		StringJoiner literal = new StringJoiner(",", "[", "]");
		for (float value : values) {
			literal.add(Float.toString(value));
		}
		return literal.toString();
	}

	private static PostgreSQLContainer start() {
		Path initScript = REPO.resolve("infra/knowledge-db/init/01-knowledge.sh");
		if (!Files.isRegularFile(initScript)) {
			throw new IllegalStateException("Init script not found at " + initScript);
		}
		PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
			.withCopyFileToContainer(MountableFile.forHostPath(initScript, 0755),
					"/docker-entrypoint-initdb.d/01-knowledge.sh")
			.withEnv("KNOWLEDGE_INDEXER_DB_PASSWORD", INDEXER_PASSWORD)
			.withEnv("KINTSUGI_AGENT_DB_PASSWORD", AGENT_PASSWORD)
			// The init script also creates the agent database and its role, agent_app (ADR-010).
			.withEnv("AGENT_DB_PASSWORD", "agent-app-test");
		container.start(); // stopped by Testcontainers' reaper when the JVM exits
		return container;
	}

}
