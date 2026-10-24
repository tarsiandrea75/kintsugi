package dev.kintsugi.agent.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kintsugi.agent.AgentDatabase;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@code agent} database as the init script and the agent's Flyway migrations build it: who may connect, who owns
 * what, and the constraints that keep the incident rows consistent (agent spec, "State tables" and "Isolation").
 */
class AgentStoreTest {

	private static final String PERMISSION_DENIED = "42501";

	private static final String CHECK_VIOLATION = "23514";

	private static final String UNIQUE_VIOLATION = "23505";

	private static final String SIGNAL = "'{\"symptom\":\"Checkout orders stay pending\",\"sinceMinutes\":15}'";

	@BeforeAll
	static void migrate() {
		AgentDatabase.migrate();
	}

	@BeforeEach
	void empty() {
		AgentDatabase.empty();
	}

	@Test
	void agentAppIsNotPrivileged() {
		Map<String, Object> role = AgentDatabase.admin()
			.sql("SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles"
					+ " WHERE rolname = 'agent_app'")
			.query()
			.singleRow();
		assertThat(role.values()).hasSize(5).containsOnly(false);
	}

	@Test
	void agentAppCannotConnectToKnowledge() {
		assertThatExceptionOfType(SQLException.class)
			.isThrownBy(() -> DriverManager
				.getConnection(AgentDatabase.url("knowledge"), "agent_app", AgentDatabase.AGENT_PASSWORD)
				.close())
			.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED))
			.withMessageContaining("CONNECT");
	}

	@ParameterizedTest
	@CsvSource({ "kintsugi_agent, " + AgentDatabase.KINTSUGI_AGENT_PASSWORD,
			"knowledge_indexer, " + AgentDatabase.KNOWLEDGE_INDEXER_PASSWORD })
	void theKnowledgeRolesCannotConnectToAgent(String role, String password) {
		assertThatExceptionOfType(SQLException.class)
			.isThrownBy(() -> DriverManager.getConnection(AgentDatabase.url("agent"), role, password).close())
			.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED))
			.withMessageContaining("CONNECT");
	}

	@Test
	void aRoleWithoutAGrantCannotConnectToAgent() {
		AgentDatabase.admin().sql("CREATE ROLE agent_stranger LOGIN PASSWORD 'stranger'").update();
		try {
			assertThatExceptionOfType(SQLException.class)
				.isThrownBy(() -> DriverManager.getConnection(AgentDatabase.url("agent"), "agent_stranger", "stranger")
					.close())
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED));
		}
		finally {
			AgentDatabase.admin().sql("DROP ROLE agent_stranger").update();
		}
	}

	@Test
	void theDatabaseStaysTheSuperusersAndFlywayCreatedTheTablesAsAgentApp() {
		JdbcClient admin = AgentDatabase.admin();
		assertThat(admin.sql("SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'agent'")
			.query(String.class)
			.single()).isEqualTo(AgentDatabase.get().getUsername());
		assertThat(admin.sql("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'")
			.query(String.class)
			.list()).containsExactly("agent_app");
		assertThat(admin.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'").query(String.class).list())
			.contains("incidents", "incident_steps", "flyway_schema_history");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ALTER DATABASE agent SET statement_timeout = 0", "CREATE SCHEMA other" })
	void agentAppCannotChangeTheDatabaseItself(String statement) throws SQLException {
		try (Connection connection = DriverManager.getConnection(AgentDatabase.url("agent"), "agent_app",
				AgentDatabase.AGENT_PASSWORD); Statement sql = connection.createStatement()) {
			assertThatExceptionOfType(SQLException.class).isThrownBy(() -> sql.execute(statement))
				.satisfies(ex -> assertThat(ex.getSQLState()).isEqualTo(PERMISSION_DENIED));
		}
	}

	@Test
	void agentAppCannotOpenTheDatabaseToAnotherRole() throws SQLException {
		// Without a grant option PostgreSQL only warns that no privileges were granted; what matters is the result.
		try (Connection connection = DriverManager.getConnection(AgentDatabase.url("agent"), "agent_app",
				AgentDatabase.AGENT_PASSWORD); Statement sql = connection.createStatement()) {
			sql.execute("GRANT CONNECT ON DATABASE agent TO kintsugi_agent");
		}
		assertThat(AgentDatabase.admin()
			.sql("SELECT has_database_privilege('kintsugi_agent', 'agent', 'CONNECT')")
			.query(Boolean.class)
			.single()).isFalse();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', value = { "RUNNING | NULL | NULL | NULL",
			"COMPLETED | now() | NULL | '{\"rootCauseService\":\"UNKNOWN\"}'",
			"INVALID_REPORT | now() | 'two invalid reports' | NULL",
			"BUDGET_EXCEEDED | now() | 'model-call budget used up' | NULL",
			"TIMED_OUT | now() | 'incident ran past 10 minutes' | NULL",
			"FAILED | now() | 'plan invalid twice' | NULL" })
	void everyStatusIsAcceptedWithItsEndFields(String status, String endedAt, String reason, String report) {
		insertIncident(status, endedAt, reason, report);

		assertThat(AgentDatabase.admin().sql("SELECT status FROM incidents").query(String.class).single())
			.isEqualTo(status);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', value = {
			// an unknown status
			"DONE | now() | 'x' | NULL",
			// a running incident has not ended, and has no reason and no report
			"RUNNING | now() | NULL | NULL", "RUNNING | NULL | 'x' | NULL", "RUNNING | NULL | NULL | '{}'",
			// an end records ended_at, and a reason unless completed
			"FAILED | NULL | 'x' | NULL", "FAILED | now() | NULL | NULL",
			// only a completed incident has a report, and it has one
			"FAILED | now() | 'x' | '{}'", "COMPLETED | now() | NULL | NULL", "COMPLETED | now() | 'x' | '{}'" })
	void inconsistentIncidentsAreRejected(String status, String endedAt, String reason, String report) {
		assertCheckViolation(() -> insertIncident(status, endedAt, reason, report));
	}

	@Test
	void aSecondRunningIncidentIsRejectedByTheUniqueIndex() {
		insertIncident("RUNNING", "NULL", "NULL", "NULL");

		assertThatExceptionOfType(Exception.class).isThrownBy(() -> insertIncident("RUNNING", "NULL", "NULL", "NULL"))
			.satisfies(ex -> {
				SQLException cause = sqlCause(ex);
				assertThat(cause.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
				assertThat(cause.getMessage()).contains("incidents_one_running_idx");
			});
		insertIncident("FAILED", "now()", "'x'", "NULL");
		assertThat(AgentDatabase.admin().sql("SELECT count(*) FROM incidents").query(Integer.class).single())
			.isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "explorer", "dossier", "decision" })
	void everyModeIsAccepted(String mode) {
		UUID incident = insertIncident("RUNNING", "NULL", "NULL", "NULL");

		recordMode(incident, mode);

		assertThat(AgentDatabase.admin().sql("SELECT mode FROM incidents").query(String.class).single())
			.isEqualTo(mode);
	}

	/**
	 * V7 dropped V4's constraint by the name PostgreSQL gave it and added it again under that name, so the rejection
	 * still names it: a later migration that changes the modes again can drop it by that name.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "triage", "DECISION", "" })
	void aModeOutsideTheSpecIsRejectedByTheModeCheck(String mode) {
		UUID incident = insertIncident("RUNNING", "NULL", "NULL", "NULL");

		assertThatExceptionOfType(Exception.class).isThrownBy(() -> recordMode(incident, mode))
			.satisfies(ex -> {
				SQLException cause = sqlCause(ex);
				assertThat(cause.getSQLState()).isEqualTo(CHECK_VIOLATION);
				assertThat(cause.getMessage()).contains("incidents_mode_check");
			});
	}

	@Test
	void stepsAcceptTheSpecValues() {
		UUID incident = insertIncident("RUNNING", "NULL", "NULL", "NULL");
		List<String> outcomes = List.of("OK", "INVALID_PARAMETER", "UPSTREAM_ERROR", "CLIENT_ERROR", "REFUSED_PHASE",
				"REFUSED_BUDGET");
		int stepNo = 1;
		for (String outcome : outcomes) {
			insertStep(incident, stepNo++, "TOOL_CALL", "TRIAGE", outcome);
		}
		for (String kind : List.of("PLAN", "REPORT", "BUDGET")) {
			insertStep(incident, stepNo++, kind, "CONCLUDE", null);
		}
		for (String phase : List.of("INVESTIGATE", "KNOWLEDGE")) {
			insertStep(incident, stepNo++, "PLAN", phase, null);
		}
		assertThat(AgentDatabase.admin().sql("SELECT count(*) FROM incident_steps").query(Integer.class).single())
			.isEqualTo(11);
	}

	@ParameterizedTest
	@CsvSource({ "TOOL_CALL, TRIAGE, SKIPPED", "THOUGHT, TRIAGE, OK", "TOOL_CALL, DONE, OK" })
	void stepsRejectValuesOutsideTheSpec(String kind, String phase, String outcome) {
		UUID incident = insertIncident("RUNNING", "NULL", "NULL", "NULL");

		assertCheckViolation(() -> insertStep(incident, 1, kind, phase, outcome));
	}

	@Test
	void stepNumbersStartAtOneAndAreUniquePerIncident() {
		UUID incident = insertIncident("RUNNING", "NULL", "NULL", "NULL");
		insertStep(incident, 1, "PLAN", "TRIAGE", null);

		assertCheckViolation(() -> insertStep(incident, 0, "PLAN", "TRIAGE", null));
		assertThatExceptionOfType(Exception.class).isThrownBy(() -> insertStep(incident, 1, "PLAN", "TRIAGE", null))
			.satisfies(ex -> assertThat(sqlCause(ex).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
	}

	private static UUID insertIncident(String status, String endedAt, String reason, String report) {
		UUID id = UUID.randomUUID();
		AgentDatabase.asAgentApp()
			.sql("INSERT INTO incidents (id, signal, status, report, failure_reason, started_at, ended_at) VALUES ('"
					+ id + "', " + SIGNAL + ", '" + status + "', " + report + ", " + reason + ", now(), " + endedAt
					+ ")")
			.update();
		return id;
	}

	/** Records the mode as the loop does when the run starts. */
	private static void recordMode(UUID incident, String mode) {
		AgentDatabase.asAgentApp().sql("UPDATE incidents SET mode = ? WHERE id = ?").params(mode, incident).update();
	}

	private static void insertStep(UUID incident, int stepNo, String kind, String phase, String outcome) {
		AgentDatabase.asAgentApp()
			.sql("INSERT INTO incident_steps (incident_id, step_no, kind, phase, tool_outcome, created_at)"
					+ " VALUES (?, ?, ?, ?, ?, now())")
			.params(incident, stepNo, kind, phase, outcome)
			.update();
	}

	private static void assertCheckViolation(Runnable insert) {
		assertThatExceptionOfType(Exception.class).isThrownBy(insert::run)
			.satisfies(ex -> assertThat(sqlCause(ex).getSQLState()).isEqualTo(CHECK_VIOLATION));
	}

	private static SQLException sqlCause(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql;
			}
		}
		throw new AssertionError("no SQLException in " + error);
	}

}
