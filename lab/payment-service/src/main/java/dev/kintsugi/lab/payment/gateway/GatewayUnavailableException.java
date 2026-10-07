package dev.kintsugi.lab.payment.gateway;

/**
 * The payment gateway gave no usable answer: a connect or read timeout, a connection error, a non-2xx response other
 * than 429, or a body without a known status. The outcome is unknown, not declined, so no payment is stored.
 */
public class GatewayUnavailableException extends RuntimeException {

	public GatewayUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
