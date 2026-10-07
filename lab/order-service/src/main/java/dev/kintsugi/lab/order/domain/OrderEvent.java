package dev.kintsugi.lab.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the append-only {@code order_events} table: something that happened to an order.
 *
 * @param id assigned by the application
 * @param orderId the order it happened to
 * @param occurredAt when the event was recorded, in microseconds (what PostgreSQL stores)
 * @param type what happened, one of {@link #TYPES}
 * @param outcome the order's status after the event
 * @param detail compact JSON, or null: the customer and items when received, or the exception class, URL and message
 * of an unavailable dependency
 * @param durationMs how long the call to the other service took, in milliseconds; null when there was no call
 */
public record OrderEvent(UUID id, UUID orderId, Instant occurredAt, String type, String outcome, String detail,
		Long durationMs) {

	/** The order reached order-service with a valid body and was stored as {@code CREATED}. */
	public static final String ORDER_RECEIVED = "ORDER_RECEIVED";

	/** inventory-service reserved every item. */
	public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";

	/** inventory-service answered 409: the stock is short. */
	public static final String INVENTORY_REJECTED = "INVENTORY_REJECTED";

	/** inventory-service gave no usable answer. */
	public static final String INVENTORY_UNAVAILABLE = "INVENTORY_UNAVAILABLE";

	/** payment-service answered {@code AUTHORIZED}. */
	public static final String PAYMENT_AUTHORIZED = "PAYMENT_AUTHORIZED";

	/** payment-service answered {@code DECLINED}. */
	public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";

	/** payment-service answered {@code FAILED}: the gateway rejected the payment with a 429 (fault F2). */
	public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

	/** payment-service gave no usable answer; the order stays {@code PAYMENT_PENDING}. */
	public static final String PAYMENT_UNAVAILABLE = "PAYMENT_UNAVAILABLE";

	/** Every type; the schema's CHECK constraint accepts exactly these. */
	public static final List<String> TYPES = List.of(ORDER_RECEIVED, INVENTORY_RESERVED, INVENTORY_REJECTED,
			INVENTORY_UNAVAILABLE, PAYMENT_AUTHORIZED, PAYMENT_DECLINED, PAYMENT_FAILED, PAYMENT_UNAVAILABLE);

}
