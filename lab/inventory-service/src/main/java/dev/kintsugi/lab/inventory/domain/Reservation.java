package dev.kintsugi.lab.inventory.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to reserve stock for an order: all lines are reserved together or none is. Lines for the same SKU add
 * up, since each one is taken in turn.
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

}
