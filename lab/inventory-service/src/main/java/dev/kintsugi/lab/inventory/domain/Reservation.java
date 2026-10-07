package dev.kintsugi.lab.inventory.domain;

import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A request to reserve stock for an order: all lines are reserved together or none is. Lines for the same SKU add
 * up.
 *
 * @param orderId the order the stock is for
 * @param lines 1 to {@value #MAX_LINES} lines, kept in order
 */
public record Reservation(UUID orderId, List<ReservationLine> lines) {

	public static final int MAX_LINES = 100;

	public Reservation {
		Objects.requireNonNull(orderId, "orderId");
		lines = List.copyOf(lines);
		if (lines.isEmpty() || lines.size() > MAX_LINES) {
			throw new IllegalArgumentException("a reservation has 1 to " + MAX_LINES + " lines, was " + lines.size());
		}
	}

	/**
	 * The quantity to take per SKU: lines for the same SKU summed, SKUs in ascending order. Taking stock in this order
	 * makes every reservation lock its rows in the same order, so two reservations naming the same SKUs in different
	 * orders cannot deadlock. Totals are {@code long} because repeated lines can add up beyond an {@code int}.
	 */
	public SortedMap<String, Long> quantitiesBySku() {
		SortedMap<String, Long> totals = new TreeMap<>();
		for (ReservationLine line : lines) {
			totals.merge(line.sku(), (long) line.quantity(), Long::sum);
		}
		return totals;
	}

}
