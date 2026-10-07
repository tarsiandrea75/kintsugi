package dev.kintsugi.lab.fault.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.kintsugi.lab.fault.domain.InjectionEvent;
import dev.kintsugi.lab.fault.domain.InjectionEventType;

/**
 * Appends and reads {@code injection_events}. The table is append-only, so this class has no update or delete on
 * purpose: a test checks that it never gains one.
 */
@Repository
public class InjectionEventRepository {

	private final JdbcClient jdbc;

	public InjectionEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Appends one event; joins the caller's transaction when there is one. {@code detail} is compact JSON or null. */
	public void append(UUID injectionId, InjectionEventType type, Instant occurredAt, String detail) {
		jdbc.sql("INSERT INTO injection_events (injection_id, occurred_at, type, detail) VALUES (?, ?, ?, ?)")
			.params(injectionId, Timestamp.from(occurredAt), type.name(), detail)
			.update();
	}

	/** The events of {@code injectionId}, in the order they were appended. */
	public List<InjectionEvent> findByInjectionId(UUID injectionId) {
		return jdbc.sql("SELECT id, injection_id, occurred_at, type, detail FROM injection_events "
				+ "WHERE injection_id = ? ORDER BY id")
			.param(injectionId)
			.query(InjectionEventRepository::map)
			.list();
	}

	private static InjectionEvent map(ResultSet rs, int rowNum) throws SQLException {
		return new InjectionEvent(rs.getLong("id"), rs.getObject("injection_id", UUID.class),
				InjectionEventType.valueOf(rs.getString("type")), rs.getTimestamp("occurred_at").toInstant(),
				rs.getString("detail"));
	}

}
