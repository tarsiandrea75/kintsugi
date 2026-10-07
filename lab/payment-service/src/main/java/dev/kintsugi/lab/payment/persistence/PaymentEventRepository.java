package dev.kintsugi.lab.payment.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.kintsugi.lab.payment.domain.PaymentEvent;

/**
 * Appends and reads {@code payment_events}. The table is append-only, so this class has no update or delete on
 * purpose: a test checks that it never gains one.
 */
@Repository
public class PaymentEventRepository {

	private static final String COLUMNS = "id, order_id, occurred_at, type, outcome, detail, duration_ms";

	private final JdbcClient jdbc;

	public PaymentEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Appends {@code event}; joins the caller's transaction when there is one. */
	public void insert(PaymentEvent event) {
		jdbc.sql("INSERT INTO payment_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")
			.params(event.id(), event.orderId(), Timestamp.from(event.occurredAt()), event.type(), event.outcome(),
					event.detail(), event.durationMs())
			.update();
	}

	/** The events of {@code orderId}, oldest first. */
	public List<PaymentEvent> findByOrderId(UUID orderId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM payment_events WHERE order_id = ? ORDER BY occurred_at, id")
			.param(orderId)
			.query(PaymentEventRepository::map)
			.list();
	}

	/** The {@code limit} most recent events of all orders, newest first. */
	public List<PaymentEvent> latest(int limit) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM payment_events ORDER BY occurred_at DESC, id LIMIT ?")
			.param(limit)
			.query(PaymentEventRepository::map)
			.list();
	}

	private static PaymentEvent map(ResultSet rs, int rowNum) throws SQLException {
		return new PaymentEvent(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
				rs.getTimestamp("occurred_at").toInstant(), rs.getString("type"), rs.getString("outcome"),
				rs.getString("detail"), rs.getObject("duration_ms", Long.class));
	}

}
