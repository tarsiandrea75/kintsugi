package dev.kintsugi.lab.order.inventory;

import java.util.UUID;

/**
 * Inventory-service gave no usable answer: a connect or read timeout, a connection error, a response other than 201
 * {@code RESERVED} or 409 {@code INSUFFICIENT_STOCK}, or a body without a known status. Whether the stock was
 * reserved is unknown.
 */
public class InventoryUnavailableException extends RuntimeException {

	private final UUID orderId;

	private final String url;

	/**
	 * @param orderId the order whose reservation failed
	 * @param url the inventory-service endpoint that was called, recorded with the order's event
	 */
	public InventoryUnavailableException(UUID orderId, String url, String message, Throwable cause) {
		super(message, cause);
		this.orderId = orderId;
		this.url = url;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public String getUrl() {
		return url;
	}

}
