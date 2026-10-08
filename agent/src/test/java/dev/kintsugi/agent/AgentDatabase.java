package dev.kintsugi.agent;

import java.nio.file.Files;
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The knowledge store as compose builds it, shared by every test class in the JVM: the pinned pgvector image running
 * the real {@code infra/knowledge-db/init/01-knowledge.sh}, which now also creates the {@code agent} database and its
 * owner role {@code agent_app} (agent spec, "State tables" and "Isolation"; ADR-010).
 */
public final class AgentDatabase {

	public static final String AGENT_PASSWORD = "agent-app-test";

	public static final String KNOWLEDGE_INDEXER_PASSWORD = "knowledge-indexer-test";

	public static final String KINTSUGI_AGENT_PASSWORD = "kintsugi-agent-knowledge-test";

	private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	/** pgvector/pgvector:0.8.7-pg18-trixie, pinned by the index digest recorded in ADR-002. */
	private static final DockerImageName IMAGE = DockerImageName
		.parse("pgvector/pgvector@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0")
		.asCompatibleSubstituteFor("postgres");

	private static PostgreSQLContainer container;

	private AgentDatabase() {
	}

	public static synchronized PostgreSQLContainer get() {
		if (container == null) {
			Path initScript = REPO.resolve("infra/knowledge-db/init/01-knowledge.sh");
			if (!Files.isRegularFile(initScript)) {
				throw new IllegalStateException("Init script not found at " + initScript);
			}
			container = new PostgreSQLContainer(IMAGE)
				.withCopyFileToContainer(MountableFile.forHostPath(initScript, 0755),
						"/docker-entrypoint-initdb.d/01-knowledge.sh")
				.withEnv("KNOWLEDGE_INDEXER_DB_PASSWORD", KNOWLEDGE_INDEXER_PASSWORD)
				.withEnv("KINTSUGI_AGENT_DB_PASSWORD", KINTSUGI_AGENT_PASSWORD)
				.withEnv("AGENT_DB_PASSWORD", AGENT_PASSWORD);
			container.start(); // stopped by Testcontainers' reaper when the JVM exits
		}
		return container;
	}

	public static String url(String database) {
		PostgreSQLContainer store = get();
		return "jdbc:postgresql://" + store.getHost() + ":" + store.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
				+ "/" + database;
	}

	/** Points the application at the {@code agent} database as {@code agent_app}, the role compose gives it. */
	public static void register(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> url("agent"));
		registry.add("spring.datasource.username", () -> "agent_app");
		registry.add("spring.datasource.password", () -> AGENT_PASSWORD);
	}

	/** Runs the agent's own Flyway migrations as {@code agent_app}, for tests without a Spring context. */
	public static void migrate() {
		Flyway.configure().dataSource(url("agent"), "agent_app", AGENT_PASSWORD).load().migrate();
	}

	/** The bootstrap superuser on the {@code agent} database, for resetting, seeding and inspecting rows only. */
	public static JdbcClient admin() {
		PostgreSQLContainer store = get();
		return JdbcClient.create(new SimpleDriverDataSource(new org.postgresql.Driver(), url("agent"),
				store.getUsername(), store.getPassword()));
	}

	/** {@code agent_app} on the {@code agent} database, as the application connects. */
	public static JdbcClient asAgentApp() {
		return JdbcClient.create(
				new SimpleDriverDataSource(new org.postgresql.Driver(), url("agent"), "agent_app", AGENT_PASSWORD));
	}

	/** Removes every incident and step. */
	public static void empty() {
		admin().sql("TRUNCATE incident_steps, incidents").update();
	}

}
