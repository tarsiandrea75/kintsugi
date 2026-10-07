package dev.kintsugi.lab.order.domain;

/**
 * Lifecycle of an order: {@code CREATED -> PAYMENT_PENDING -> PAID | PAYMENT_FAILED}, or
 * {@code CREATED -> REJECTED_OUT_OF_STOCK | REJECTED_INVENTORY_UNAVAILABLE} when the stock could not be reserved.
 * {@code PAID}, {@code PAYMENT_FAILED} and both {@code REJECTED_*} statuses are terminal.
 */
public enum OrderStatus {

	CREATED, PAYMENT_PENDING, PAID, PAYMENT_FAILED,

	/** inventory-service answered 409: a line was short or its SKU unknown. */
	REJECTED_OUT_OF_STOCK,

	/** inventory-service gave no usable answer; whether it reserved the stock is unknown. */
	REJECTED_INVENTORY_UNAVAILABLE;

	public boolean canTransitionTo(OrderStatus target) {
		return switch (this) {
			case CREATED ->
				target == PAYMENT_PENDING || target == REJECTED_OUT_OF_STOCK || target == REJECTED_INVENTORY_UNAVAILABLE;
			case PAYMENT_PENDING -> target == PAID || target == PAYMENT_FAILED;
			case PAID, PAYMENT_FAILED, REJECTED_OUT_OF_STOCK, REJECTED_INVENTORY_UNAVAILABLE -> false;
		};
	}

}
