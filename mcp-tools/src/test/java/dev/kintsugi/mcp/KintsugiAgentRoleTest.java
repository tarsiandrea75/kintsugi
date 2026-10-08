package dev.kintsugi.mcp;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What {@code kintsugi_agent} can and cannot do, against the lab database as the real init script and the services'
 * real migrations build it (mcp-tools spec, "Database access runs as kintsugi_agent"; ADR-004).
 */
class KintsugiAgentRoleTest {

	private static final String PERMISSION_DENIED = "42501";

	private static final String READ_ONLY_TRANSACTION = "25006";

	private static final String QUERY_CANCELED = "57014";

	/** The evidence tables, by database. */
	private static final Map<String, String[]> EVIDENCE = Map.of("orders", new String[] { "orders", "order_events" },
			"payments", new String[] { "payments", "payment_events" }, "inventory",
			new String[] { "stock", "reservation_events" });

	@Test
	void canSelectTheSixEvidenceTables() throws SQLException {
		for (Map.Entry<String, String[]> database : EVIDENCE.entrySet()) {
			try (Connection connection = LabDatabase.asAgent(database.getKey())) {
				for (String table : database.getValue()) {
					assertThat(count(connection, table)).as(table).isGreaterThanOrEqualTo(0);
				}
			}
		}
	}

	@ParameterizedTest
	@CsvSource({ "orders, order_items", "orders, flyway_schema_history", "payments, flyway_schema_history",
			"inventory, flyway_schema_history" })
	void cannotSelectAnyOtherTable(String database, String table) throws SQLException {
		try (Connection connection = LabDatabase.asAgent(database)) {
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> count(connection, table))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED));
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"orders | INSERT INTO orders VALUES (gen_random_uuid(), 'c', 'CREATED', 0, now(), now())",
			"orders | UPDATE orders SET status = 'PAID'", "orders | DELETE FROM order_events",
			"payments | INSERT INTO payment_events (id, order_id, occurred_at, type) VALUES (gen_random_uuid(), gen_random_uuid(), now(), 'PAYMENT_ATTEMPT')",
			"inventory | UPDATE stock SET available = 0", "inventory | DELETE FROM reservation_events" })
	void cannotWriteEvenAfterLeavingReadOnlyMode(String database, String statement) throws SQLException {
		try (Connection connection = LabDatabase.asAgent(database); Statement sql = connection.createStatement()) {
			// The session default stops the write first...
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> sql.execute(statement))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(READ_ONLY_TRANSACTION));
			// ...and the missing grant stops it when the session turns that default off.
			sql.execute("SET default_transaction_read_only = off");
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> sql.execute(statement))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED));
		}
	}

	@Test
	void cannotConnectToTheFaultsDatabase() {
		assertThatExceptionOfType(SQLException.class).isThrownBy(() -> LabDatabase.asAgent("faults").close())
			.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED))
			.withMessageContaining("CONNECT");
	}

	@Test
	void isNotPrivileged() throws SQLException {
		try (Connection connection = LabDatabase.asAgent("orders"); Statement sql = connection.createStatement();
				ResultSet role = sql.executeQuery("SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication,"
						+ " rolbypassrls FROM pg_roles WHERE rolname = 'kintsugi_agent'")) {
			assertThat(role.next()).isTrue();
			for (int column = 1; column <= 5; column++) {
				assertThat(role.getBoolean(column)).as(role.getMetaData().getColumnName(column)).isFalse();
			}
		}
	}

	@Test
	void sessionsStartWithTheRoleSettings() throws SQLException {
		try (Connection connection = LabDatabase.asAgent("payments")) {
			assertThat(show(connection, "default_transaction_read_only")).isEqualTo("on");
			assertThat(show(connection, "statement_timeout")).isEqualTo("3s");
			assertThat(show(connection, "transaction_timeout")).isEqualTo("5s");
			assertThat(show(connection, "idle_in_transaction_session_timeout")).isEqualTo("5s");
		}
	}

	@Test
	void aLongStatementIsCanceledAfterThreeSeconds() throws SQLException {
		try (Connection connection = LabDatabase.asAgent("inventory"); Statement sql = connection.createStatement()) {
			long start = System.nanoTime();
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> sql.execute("SELECT pg_sleep(10)"))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(QUERY_CANCELED));
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(2900),
					Duration.ofSeconds(6));
		}
	}

	private static long count(Connection connection, String table) throws SQLException {
		try (Statement sql = connection.createStatement();
				ResultSet rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
			rows.next();
			return rows.getLong(1);
		}
	}

	private static String show(Connection connection, String setting) throws SQLException {
		try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SHOW " + setting)) {
			rows.next();
			return rows.getString(1);
		}
	}

}
