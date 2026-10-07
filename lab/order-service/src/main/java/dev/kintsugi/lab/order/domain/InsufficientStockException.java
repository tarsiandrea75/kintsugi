package dev.kintsugi.lab.order.domain;

import java.util.UUID;

/**
 * The order cannot be placed because inventory-service could not reserve every item: one was short or unknown.
 * Nothing was reserved and nothing was stored.
 */
public class InsufficientStockException extends RuntimeException {

	private final UUID orderId;

	public InsufficientStockException(UUID orderId) {
		super("Insufficient stock for order " + orderId);
		this.orderId = orderId;
	}

	public UUID getOrderId() {
		return orderId;
	}

}
