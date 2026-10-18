package dev.kintsugi.agent.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.incident.IncidentViews.Incident;
import dev.kintsugi.agent.incident.IncidentViews.Step;

/**
 * Reads and writes {@code incidents} and {@code incident_steps} in the {@code agent} database, as {@code agent_app}.
 * The API opens and reads incidents; the loop writes their steps and ends them.
 */
@Repository
@ConditionalOnWebApplication
public class IncidentRepository {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public IncidentRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Inserts a {@code RUNNING} incident. While another is running, the one-{@code RUNNING} partial unique index fails
	 * the insert with {@link org.springframework.dao.DuplicateKeyException}: the check and the insert are one atomic
	 * statement.
	 */
	void insertRunning(UUID id, IncidentSignal signal, Instant startedAt) {
		jdbc.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, CAST(? AS jsonb), ?, ?)")
			.params(id, json.writeValueAsString(signal), IncidentStatus.RUNNING.name(), Timestamp.from(startedAt))
			.update();
	}

	/** The running incident, if any; there is at most one. */
	Optional<UUID> findRunningId() {
		return jdbc.sql("SELECT id FROM incidents WHERE status = ?")
			.param(IncidentStatus.RUNNING.name())
			.query(UUID.class)
			.optional();
	}

	/** The incident and its steps in step order. */
	Optional<Incident> find(UUID id) {
		return jdbc.sql("""
				SELECT id, signal->>'symptom' AS symptom, (signal->>'sinceMinutes')::int AS since_minutes, mode, route,
				       model, status, failure_reason, started_at, ended_at, report::text AS report
				FROM incidents WHERE id = ?""")
			.param(id)
			.query(this::incident)
			.optional()
			.map(incident -> incident.withSteps(steps(id)));
	}

	/** Fails every {@code RUNNING} incident with {@code reason}; returns how many there were. */
	int failRunning(String reason, Instant endedAt) {
		return jdbc.sql("UPDATE incidents SET status = ?, failure_reason = ?, ended_at = ? WHERE status = ?")
			.params(IncidentStatus.FAILED.name(), reason, Timestamp.from(endedAt), IncidentStatus.RUNNING.name())
			.update();
	}

	/** Fails the incident {@code id} with {@code reason} if it is still {@code RUNNING}; returns whether it was. */
	boolean fail(UUID id, String reason, Instant endedAt) {
		return end(id, IncidentStatus.FAILED, reason, endedAt);
	}

	/**
	 * Ends the incident {@code id} with {@code status}, neither {@code RUNNING} nor {@code COMPLETED}, and its
	 * {@code reason}, if it is still {@code RUNNING}; returns whether it was.
	 */
	public boolean end(UUID id, IncidentStatus status, String reason, Instant endedAt) {
		if (status == IncidentStatus.RUNNING || status == IncidentStatus.COMPLETED) {
			throw new IllegalArgumentException("not an end with a reason: " + status);
		}
		return jdbc.sql("UPDATE incidents SET status = ?, failure_reason = ?, ended_at = ? WHERE id = ? AND status = ?")
			.params(status.name(), reason, Timestamp.from(endedAt), id, IncidentStatus.RUNNING.name())
			.update() == 1;
	}

	/** Completes the incident {@code id} with its validated report, if it is still {@code RUNNING}. */
	public boolean complete(UUID id, String report, Instant endedAt) {
		return jdbc.sql("UPDATE incidents SET status = ?, report = CAST(? AS jsonb), ended_at = ? "
				+ "WHERE id = ? AND status = ?")
			.params(IncidentStatus.COMPLETED.name(), report, Timestamp.from(endedAt), id, IncidentStatus.RUNNING.name())
			.update() == 1;
	}

	/** The signal and start time of the incident {@code id}, if it is {@code RUNNING}: what the loop starts from. */
	public Optional<OpenIncident> findRunning(UUID id) {
		return jdbc.sql("""
				SELECT signal->>'symptom' AS symptom, (signal->>'sinceMinutes')::int AS since_minutes, started_at
				FROM incidents WHERE id = ? AND status = ?""")
			.params(id, IncidentStatus.RUNNING.name())
			.query((rs, rowNum) -> new OpenIncident(id, rs.getString("symptom"), rs.getInt("since_minutes"),
					instant(rs, "started_at")))
			.optional();
	}

	/**
	 * Records how the run goes, when it starts, on the incident {@code id} if it is still {@code RUNNING}: the loop's
	 * mode, {@code explorer} or {@code dossier}, its route, {@code local} or {@code cloud}, and the route's model;
	 * returns whether it was.
	 */
	public boolean recordStart(UUID id, String mode, String route, String model) {
		return jdbc.sql("UPDATE incidents SET mode = ?, route = ?, model = ? WHERE id = ? AND status = ?")
			.params(mode, route, model, id, IncidentStatus.RUNNING.name())
			.update() == 1;
	}

	/** Writes one step of the incident {@code id}; a step is written when it ends, before the next one starts. */
	public void insertStep(UUID id, StepRow step) {
		jdbc.sql("""
				INSERT INTO incident_steps (incident_id, step_no, kind, phase, model, tokens, prompt_tokens,
				                            completion_tokens, tool_name, tool_args, tool_output, tool_outcome, summary,
				                            model_output, latency_ms, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)""")
			.params(id, step.stepNo(), step.kind(), step.phase(), step.model(), step.tokens(), step.promptTokens(),
					step.completionTokens(), step.toolName(), step.toolArgs(), step.toolOutput(), step.toolOutcome(),
					step.summary(), step.modelOutput(), step.latencyMs(), Timestamp.from(step.createdAt()))
			.update();
	}

	private List<Step> steps(UUID id) {
		return jdbc.sql("""
				SELECT step_no, kind, phase, model, tokens, prompt_tokens, completion_tokens, tool_name,
				       tool_args::text AS tool_args, tool_outcome, summary, tool_output, model_output, latency_ms,
				       created_at
				FROM incident_steps WHERE incident_id = ? ORDER BY step_no""")
			.param(id)
			.query(this::step)
			.list();
	}

	private Incident incident(ResultSet rs, int rowNum) throws SQLException {
		return new Incident(rs.getObject("id", UUID.class), rs.getString("symptom"),
				rs.getObject("since_minutes", Integer.class), rs.getString("mode"),
				rs.getString("route"), rs.getString("model"), IncidentStatus.valueOf(rs.getString("status")),
				rs.getString("failure_reason"),
				instant(rs, "started_at"), instant(rs, "ended_at"), tree(rs.getString("report")), List.of());
	}

	private Step step(ResultSet rs, int rowNum) throws SQLException {
		return new Step(rs.getInt("step_no"), rs.getString("kind"), rs.getString("phase"), rs.getString("model"),
				rs.getObject("tokens", Integer.class), rs.getObject("prompt_tokens", Integer.class),
				rs.getObject("completion_tokens", Integer.class), rs.getString("tool_name"),
				tree(rs.getString("tool_args")), rs.getString("tool_outcome"), rs.getString("summary"),
				rs.getString("tool_output"), rs.getString("model_output"), rs.getObject("latency_ms", Integer.class),
				instant(rs, "created_at"));
	}

	private JsonNode tree(String text) {
		return text == null ? null : json.readTree(text);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
