package dev.kintsugi.lab.order.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.kintsugi.lab.order.domain.OrderEvent;

/**
 * Appends and reads {@code order_events}. The table is append-only, so this class has no update or delete on purpose:
 * a test checks that it never gains one.
 */
@Repository
public class OrderEventRepository {

	private static final String COLUMNS = "id, order_id, occurred_at, type, outcome, detail, duration_ms";

	private final JdbcClient jdbc;

	public OrderEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Appends {@code event}; joins the caller's transaction when there is one. */
	public void insert(OrderEvent event) {
		jdbc.sql("INSERT INTO order_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")
			.params(event.id(), event.orderId(), Timestamp.from(event.occurredAt()), event.type(), event.outcome(),
					event.detail(), event.durationMs())
			.update();
	}

	/** The events of {@code orderId}, oldest first. */
	public List<OrderEvent> findByOrderId(UUID orderId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM order_events WHERE order_id = ? ORDER BY occurred_at, id")
			.param(orderId)
			.query(OrderEventRepository::map)
			.list();
	}

	/** The {@code limit} most recent events of all orders, newest first. */
	public List<OrderEvent> latest(int limit) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM order_events ORDER BY occurred_at DESC, id LIMIT ?")
			.param(limit)
			.query(OrderEventRepository::map)
			.list();
	}

	private static OrderEvent map(ResultSet rs, int rowNum) throws SQLException {
		return new OrderEvent(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
				rs.getTimestamp("occurred_at").toInstant(), rs.getString("type"), rs.getString("outcome"),
				rs.getString("detail"), rs.getObject("duration_ms", Long.class));
	}

}
