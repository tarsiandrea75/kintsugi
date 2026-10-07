package dev.kintsugi.lab.inventory.persistence;

import java.time.Duration;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.kintsugi.lab.inventory.domain.Stock;

/**
 * Reads and takes stock with plain SQL. A take is one conditional update, so concurrent reservations never take the
 * same units and stock never goes below zero.
 */
@Repository
public class StockRepository {

	private final JdbcClient jdbc;

	public StockRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Stock> find(String sku) {
		return jdbc.sql("SELECT sku, available FROM stock WHERE sku = ?")
			.param(sku)
			.query((rs, rowNum) -> new Stock(rs.getString("sku"), rs.getInt("available")))
			.optional();
	}

	/**
	 * Takes {@code quantity} units of {@code sku} if that many are available.
	 * @param quantity units to take, positive: zero would "succeed" without taking anything and a negative quantity
	 * would add stock
	 * @return {@code false} when the SKU is unknown or has fewer units; nothing changes then
	 * @throws IllegalArgumentException when {@code quantity} is zero or negative
	 */
	public boolean take(String sku, int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, was " + quantity);
		}
		int updated = jdbc.sql("UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?")
			.params(quantity, sku, quantity)
			.update();
		return updated == 1;
	}

	/**
	 * Lab-only fault hook: waits on the database, holding the current connection for {@code delay}. Called inside
	 * the reservation transaction, it makes a pooled connection busy, which is how fault F4 exhausts the pool.
	 */
	public void sleep(Duration delay) {
		jdbc.sql("SELECT pg_sleep(?)").param(delay.toNanos() / 1_000_000_000.0).query().singleRow();
	}

}
