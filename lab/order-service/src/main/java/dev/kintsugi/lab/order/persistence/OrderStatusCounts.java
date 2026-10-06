package dev.kintsugi.lab.order.persistence;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.kintsugi.lab.order.domain.OrderStatus;

/**
 * Counts stored orders per status for the {@code lab.orders} gauge, with its own statement timeout.
 *
 * <p>Only this query is bounded. A hung count query would otherwise hold the gauge's single-flight refresh forever;
 * with the timeout, the driver cancels it and the next refresh can run. The rest of the service keeps the default (no
 * timeout), because fault F4 injects slow queries on purpose and they must stay slow.
 */
@Component
public class OrderStatusCounts {

	/** Far longer than a healthy {@code GROUP BY} on the lab table, far shorter than a scrape-visible outage. */
	public static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);

	private static final String SQL = "SELECT status, count(*) AS count FROM orders GROUP BY status";

	private final JdbcTemplate jdbc;

	@Autowired
	public OrderStatusCounts(DataSource dataSource) {
		this(dataSource, QUERY_TIMEOUT);
	}

	/** {@code timeout} is rounded down to whole seconds, the JDBC unit; it must be at least 1 second. */
	public OrderStatusCounts(DataSource dataSource, Duration timeout) {
		if (timeout.toSeconds() < 1) {
			throw new IllegalArgumentException("timeout must be at least 1 second: " + timeout);
		}
		// A private template, so the per-statement timeout never leaks to other queries.
		this.jdbc = new JdbcTemplate(dataSource);
		this.jdbc.setQueryTimeout((int) timeout.toSeconds());
	}

	/**
	 * One row per status that has at least one order; statuses with no orders are absent.
	 * @throws org.springframework.dao.DataAccessException when the query fails or exceeds the timeout
	 */
	public List<OrderStatusCount> countOrdersPerStatus() {
		return jdbc.query(SQL,
				(rs, rowNum) -> new OrderStatusCount(OrderStatus.valueOf(rs.getString("status")), rs.getLong("count")));
	}

}
