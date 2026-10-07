package dev.kintsugi.lab.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only {@code payment_events} table: something that happened to an order's payment.
 *
 * @param id assigned by the application
 * @param orderId the order being paid
 * @param occurredAt when the event was recorded, in microseconds (what PostgreSQL stores)
 * @param type what happened; today always {@value #PAYMENT_ATTEMPT}
 * @param outcome a {@link PaymentStatus} name, or {@value #GATEWAY_UNAVAILABLE}
 * @param detail compact JSON: the payment id, or the exception class, URL and message
 * @param durationMs how long the attempt took, gateway call included, in milliseconds
 */
public record PaymentEvent(UUID id, UUID orderId, Instant occurredAt, String type, String outcome, String detail,
		Long durationMs) {

	/** A {@code POST /payments} that reached the service with a valid body. */
	public static final String PAYMENT_ATTEMPT = "PAYMENT_ATTEMPT";

	/** Outcome of an attempt whose gateway gave no usable answer; no payment is stored for it. */
	public static final String GATEWAY_UNAVAILABLE = "GATEWAY_UNAVAILABLE";

}
