package dev.kintsugi.lab.inventory.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only {@code reservation_events} table: something that happened to an order's reservation.
 *
 * @param id assigned by the application
 * @param orderId the order the reservation was for
 * @param occurredAt when the event was recorded, in microseconds (what PostgreSQL stores)
 * @param type what happened; today always {@value #RESERVATION_ATTEMPT}
 * @param outcome the result, a {@link ReservationStatus} name for an attempt
 * @param detail free text; for an attempt, the requested lines as compact JSON
 * @param durationMs how long it took, in milliseconds
 */
public record ReservationEvent(UUID id, UUID orderId, Instant occurredAt, String type, String outcome, String detail,
		Long durationMs) {

	/** A {@code POST /reservations} that reached the service with a valid body. */
	public static final String RESERVATION_ATTEMPT = "RESERVATION_ATTEMPT";

}
