package dev.kintsugi.lab.order.payment;

/**
 * Payment-service gave no usable answer: a connect or read timeout, a connection error, a non-2xx response, or a
 * body without a known status. The outcome of the payment is unknown, not declined.
 */
public class PaymentUnavailableException extends RuntimeException {

	public PaymentUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
