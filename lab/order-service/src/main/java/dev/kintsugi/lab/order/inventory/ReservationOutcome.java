package dev.kintsugi.lab.order.inventory;

/** What inventory-service decided for an order's items; mirrors its {@code ReservationStatus}. */
public enum ReservationOutcome {

	/** Every item was reserved (201). */
	RESERVED,

	/** At least one item was short or unknown, so nothing was reserved (409). */
	INSUFFICIENT_STOCK

}
