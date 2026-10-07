package dev.kintsugi.lab.order.payment;

import java.util.UUID;

/**
 * Payment-service gave no usable answer: a connect or read timeout, a connection error, a non-2xx response, or a
 * body without a known status. The outcome of the payment is unknown, not declined.
 */
public class PaymentUnavailableException extends RuntimeException {

	private final UUID orderId;

	private final String url;

	/**
	 * @param orderId the order whose payment failed
	 * @param url the payment-service endpoint that was called, recorded with the order's event
	 */
	public PaymentUnavailableException(UUID orderId, String url, String message, Throwable cause) {
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
