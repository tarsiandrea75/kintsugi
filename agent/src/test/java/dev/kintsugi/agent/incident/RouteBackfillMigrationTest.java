package dev.kintsugi.agent.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.kintsugi.agent.AgentDatabase;

/**
 * Migration V6 backfills the route and model of the incidents stored before it (agent spec, "Routes"): an incident whose
 * run started, which shows in its steps, was a local run with the model its steps name; one that never started has
 * neither, although V4 gave it a mode. The migrations run on a scratch database of the test store, first up to V5, then
 * V6 over rows stored in between.
 */
class RouteBackfillMigrationTest {

	private static final String DATABASE = "route_backfill";

	@Test
	void onlyIncidentsWithStepsGetTheLocalRouteAndTheirModel() {
		JdbcClient admin = AgentDatabase.admin();
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE).update();
		admin.sql("CREATE DATABASE " + DATABASE).update();
		PostgreSQLContainer store = AgentDatabase.get();
		String url = AgentDatabase.url(DATABASE);
		JdbcClient scratch = JdbcClient.create(new SimpleDriverDataSource(new org.postgresql.Driver(), url,
				store.getUsername(), store.getPassword()));

		Flyway.configure().dataSource(url, store.getUsername(), store.getPassword()).target("5").load().migrate();
		UUID ran = incident(scratch);
		UUID neverStarted = incident(scratch);
		scratch.sql("INSERT INTO incident_steps (incident_id, step_no, kind, phase, model, created_at)"
				+ " VALUES (?, 1, 'ACT', 'TRIAGE', 'granite4.1:8b', now())")
			.param(ran)
			.update();
		Flyway.configure().dataSource(url, store.getUsername(), store.getPassword()).load().migrate();

		assertThat(routeAndModel(scratch, ran)).containsEntry("route", "local").containsEntry("model", "granite4.1:8b");
		assertThat(routeAndModel(scratch, neverStarted)).containsEntry("route", null).containsEntry("model", null);
	}

	/** An incident as V5 stores it; the mode is the one V4 gave every earlier incident. */
	private static UUID incident(JdbcClient scratch) {
		UUID id = UUID.randomUUID();
		scratch.sql("INSERT INTO incidents (id, signal, status, failure_reason, mode, started_at, ended_at)"
				+ " VALUES (?, CAST('{}' AS jsonb), 'FAILED', 'the agent restarted', 'explorer', now(), now())")
			.param(id)
			.update();
		return id;
	}

	private static Map<String, Object> routeAndModel(JdbcClient scratch, UUID id) {
		return scratch.sql("SELECT route, model FROM incidents WHERE id = ?").param(id).query().singleRow();
	}

}
