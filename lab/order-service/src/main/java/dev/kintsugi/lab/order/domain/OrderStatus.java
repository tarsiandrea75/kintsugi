package dev.kintsugi.lab.order.domain;

/**
 * Lifecycle of an order: {@code CREATED -> PAYMENT_PENDING -> PAID | PAYMENT_FAILED}.
 * {@code PAID} and {@code PAYMENT_FAILED} are terminal.
 */
public enum OrderStatus {

	CREATED, PAYMENT_PENDING, PAID, PAYMENT_FAILED;

	public boolean canTransitionTo(OrderStatus target) {
		return switch (this) {
			case CREATED -> target == PAYMENT_PENDING;
			case PAYMENT_PENDING -> target == PAID || target == PAYMENT_FAILED;
			case PAID, PAYMENT_FAILED -> false;
		};
	}

}
