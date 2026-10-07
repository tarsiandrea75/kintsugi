package dev.kintsugi.lab.fault.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.kintsugi.lab.fault.domain.Injection;
import dev.kintsugi.lab.fault.domain.InjectionStatus;

/**
 * Writes and reads {@code injections}. The truth is written once: this class can insert a row, read it and move a
 * blocking row's status, and nothing else, on purpose. A test checks that it never gains a delete or another update.
 */
@Repository
public class InjectionRepository {

	private static final String COLUMNS = "id, fault_id, target_service, root_cause_service, root_cause_category, "
			+ "root_cause, parameters::text AS parameters, injected_at, restored_at, status";

	private final JdbcClient jdbc;

	public InjectionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts {@code injection}; joins the caller's transaction when there is one. A second blocking row fails with
	 * {@link org.springframework.dao.DuplicateKeyException} (partial unique index).
	 */
	public void insert(Injection injection) {
		jdbc.sql("""
				INSERT INTO injections (id, fault_id, target_service, root_cause_service, root_cause_category,
				                        root_cause, parameters, injected_at, restored_at, status)
				VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""")
			.params(injection.id(), injection.faultId(), injection.targetService(), injection.rootCauseService(),
					injection.rootCauseCategory(), injection.rootCause(), injection.parametersJson(),
					Timestamp.from(injection.injectedAt()), timestamp(injection.restoredAt()), injection.status().name())
			.update();
	}

	public Optional<Injection> findById(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM injections WHERE id = ?")
			.param(id)
			.query(InjectionRepository::map)
			.optional();
	}

	/** The blocking injection ({@code ACTIVE} or {@code RESTORE_PENDING}), if any. There is at most one. */
	public Optional<Injection> findBlocking() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM injections WHERE status IN ('ACTIVE', 'RESTORE_PENDING')")
			.query(InjectionRepository::map)
			.optional();
	}

	/**
	 * Moves a blocking injection to {@code status} and sets {@code restored_at} (null keeps it unset). A row that is
	 * no longer blocking is left unchanged.
	 * @return whether the row was blocking and has been updated
	 */
	public boolean updateStatus(UUID id, InjectionStatus status, Instant restoredAt) {
		return jdbc.sql("""
				UPDATE injections SET status = ?, restored_at = ?
				WHERE id = ? AND status IN ('ACTIVE', 'RESTORE_PENDING')""")
			.params(status.name(), timestamp(restoredAt), id)
			.update() == 1;
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static Injection map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp restoredAt = rs.getTimestamp("restored_at");
		return new Injection(rs.getObject("id", UUID.class), rs.getString("fault_id"), rs.getString("target_service"),
				rs.getString("root_cause_service"), rs.getString("root_cause_category"), rs.getString("root_cause"),
				rs.getString("parameters"), rs.getTimestamp("injected_at").toInstant(),
				restoredAt == null ? null : restoredAt.toInstant(), InjectionStatus.valueOf(rs.getString("status")));
	}

}
