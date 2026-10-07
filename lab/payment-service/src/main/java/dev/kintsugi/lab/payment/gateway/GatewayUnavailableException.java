package dev.kintsugi.lab.payment.gateway;

import java.net.URI;

/**
 * The payment gateway gave no usable answer: a connect or read timeout, a connection error, a non-2xx response other
 * than 429, or a body without a known status. The outcome is unknown, not declined, so no payment is stored.
 */
public class GatewayUnavailableException extends RuntimeException {

	private final URI url;

	public GatewayUnavailableException(String message, Throwable cause) {
		this(message, null, cause);
	}

	/** @param url the gateway endpoint that was called, recorded with the failed attempt; may be null */
	public GatewayUnavailableException(String message, URI url, Throwable cause) {
		super(message, cause);
		this.url = url;
	}

	/** The gateway endpoint that was called, or null when unknown. */
	public URI getUrl() {
		return url;
	}

}
