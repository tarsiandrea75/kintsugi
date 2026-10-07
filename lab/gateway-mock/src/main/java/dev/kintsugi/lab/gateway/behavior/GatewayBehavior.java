package dev.kintsugi.lab.gateway.behavior;

import java.util.Set;

/**
 * How the simulated gateway behaves right now. Immutable, so a reader always sees one consistent behavior.
 *
 * @param latencyMs how long {@code POST /authorize} waits before answering, from {@link #MIN_LATENCY_MS} to
 *     {@link #MAX_LATENCY_MS}
 * @param errorStatus the HTTP status {@code POST /authorize} answers with after the latency, one of
 *     {@link #ALLOWED_ERROR_STATUSES}; {@code null} means no error, so the payment is authorized
 */
public record GatewayBehavior(long latencyMs, Integer errorStatus) {

	/** Lower bound for the latency: no added wait. */
	public static final long MIN_LATENCY_MS = 0;

	/** Upper bound for the latency: one minute is far past every client timeout in the lab. */
	public static final long MAX_LATENCY_MS = 60_000;

	/**
	 * The error statuses a real gateway plausibly returns: 429 is fault F2 (rate limiting); 500 and 503 let the lab
	 * check that other errors still read as "gateway unavailable".
	 */
	public static final Set<Integer> ALLOWED_ERROR_STATUSES = Set.of(429, 500, 503);

	/** A healthy gateway: no added latency and no error. */
	public static final GatewayBehavior DEFAULT = new GatewayBehavior(MIN_LATENCY_MS, null);

	public GatewayBehavior {
		if (latencyMs < MIN_LATENCY_MS || latencyMs > MAX_LATENCY_MS) {
			throw new IllegalArgumentException(
					"Latency must be between " + MIN_LATENCY_MS + " and " + MAX_LATENCY_MS + " ms, was " + latencyMs);
		}
		if (errorStatus != null && !ALLOWED_ERROR_STATUSES.contains(errorStatus)) {
			throw new IllegalArgumentException(
					"Error status must be one of " + ALLOWED_ERROR_STATUSES + " or null, was " + errorStatus);
		}
	}

	/** A behavior with the given latency and no error. */
	public GatewayBehavior(long latencyMs) {
		this(latencyMs, null);
	}

}
