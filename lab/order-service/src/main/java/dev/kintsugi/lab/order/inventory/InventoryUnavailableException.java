package dev.kintsugi.lab.order.inventory;

/**
 * Inventory-service gave no usable answer: a connect or read timeout, a connection error, a response other than 201
 * {@code RESERVED} or 409 {@code INSUFFICIENT_STOCK}, or a body without a known status. Whether the stock was
 * reserved is unknown.
 */
public class InventoryUnavailableException extends RuntimeException {

	public InventoryUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
