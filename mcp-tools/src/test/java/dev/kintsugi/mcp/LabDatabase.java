package dev.kintsugi.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The lab's PostgreSQL as compose builds it, shared by every test in the JVM: the real
 * {@code infra/postgres/init/01-roles-and-databases.sh} runs as the image's init script, then the real Flyway migrations
 * of order-service, payment-service and inventory-service run as {@code lab_app}, in that order of events. So the
 * {@code kintsugi_agent} grants under test are the ones the lab gets.
 */
public final class LabDatabase {

	public static final String LAB_APP_PASSWORD = "lab-app-test";

	public static final String FAULTS_PASSWORD = "faults-test";

	public static final String AGENT_PASSWORD = "kintsugi-agent-test";

	private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	private static final PostgreSQLContainer CONTAINER = start();

	private LabDatabase() {
	}

	public static String host() {
		return CONTAINER.getHost();
	}

	public static int port() {
		return CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
	}

	public static String jdbcUrl(String database) {
		return "jdbc:postgresql://" + host() + ":" + port() + "/" + database;
	}

	/** A raw connection as {@code kintsugi_agent}, with the role's own session settings. */
	public static Connection asAgent(String database) throws SQLException {
		return DriverManager.getConnection(jdbcUrl(database), "kintsugi_agent", AGENT_PASSWORD);
	}

	/** A raw connection as {@code lab_app}, the owner of the lab tables, to seed test data. */
	public static Connection asLabApp(String database) throws SQLException {
		return DriverManager.getConnection(jdbcUrl(database), "lab_app", LAB_APP_PASSWORD);
	}

	/** Empties the tables the tools read, keeping the stock seed of inventory-service's V2 migration. */
	public static void clearEvidence() {
		execute("orders", "TRUNCATE orders, order_items, order_events");
		execute("payments", "TRUNCATE payments, payment_events");
		execute("inventory", "TRUNCATE reservation_events");
	}

	public static void execute(String database, String sql) {
		try (Connection connection = asLabApp(database); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not run on " + database + ": " + sql, ex);
		}
	}

	private static PostgreSQLContainer start() {
		Path initScript = REPO.resolve("infra/postgres/init/01-roles-and-databases.sh");
		if (!Files.isRegularFile(initScript)) {
			throw new IllegalStateException("Init script not found at " + initScript);
		}
		// Pinned to the PostgreSQL version chosen in ADR-007 (docs/adr/ADR-007-lab-persistence-stack.md).
		PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
			.withCopyFileToContainer(MountableFile.forHostPath(initScript, 0755),
					"/docker-entrypoint-initdb.d/01-roles-and-databases.sh")
			.withEnv("LAB_APP_DB_PASSWORD", LAB_APP_PASSWORD)
			.withEnv("FAULTS_DB_PASSWORD", FAULTS_PASSWORD)
			.withEnv("KINTSUGI_AGENT_DB_PASSWORD", AGENT_PASSWORD);
		container.start();
		migrate(container, "orders", "order-service");
		migrate(container, "payments", "payment-service");
		migrate(container, "inventory", "inventory-service");
		return container;
	}

	private static void migrate(PostgreSQLContainer container, String database, String service) {
		Path migrations = REPO.resolve("lab/" + service + "/src/main/resources/db/migration");
		String url = "jdbc:postgresql://" + container.getHost() + ":"
				+ container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
		Flyway.configure()
			.dataSource(url, "lab_app", LAB_APP_PASSWORD)
			.locations("filesystem:" + migrations)
			.load()
			.migrate();
	}

}
