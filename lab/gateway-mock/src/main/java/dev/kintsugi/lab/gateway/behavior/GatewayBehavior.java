package dev.kintsugi.lab.gateway.behavior;

/**
 * How the simulated gateway behaves right now. Immutable, so a reader always sees one consistent behavior.
 *
 * @param latencyMs how long {@code POST /authorize} waits before answering, from {@link #MIN_LATENCY_MS} to
 *     {@link #MAX_LATENCY_MS}
 */
public record GatewayBehavior(long latencyMs) {

	/** Lower bound for the latency: no added wait. */
	public static final long MIN_LATENCY_MS = 0;

	/** Upper bound for the latency: one minute is far past every client timeout in the lab. */
	public static final long MAX_LATENCY_MS = 60_000;

	/** A healthy gateway: no added latency. */
	public static final GatewayBehavior DEFAULT = new GatewayBehavior(MIN_LATENCY_MS);

	public GatewayBehavior {
		if (latencyMs < MIN_LATENCY_MS || latencyMs > MAX_LATENCY_MS) {
			throw new IllegalArgumentException(
					"Latency must be between " + MIN_LATENCY_MS + " and " + MAX_LATENCY_MS + " ms, was " + latencyMs);
		}
	}

}
