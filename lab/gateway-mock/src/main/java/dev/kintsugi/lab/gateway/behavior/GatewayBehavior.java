package dev.kintsugi.lab.gateway.behavior;

/**
 * How the simulated gateway behaves right now. Immutable, so a reader always sees one consistent behavior.
 *
 * @param latencyMs how long {@code POST /authorize} waits before answering, from 0 to {@link #MAX_LATENCY_MS}
 */
public record GatewayBehavior(long latencyMs) {

	/** Upper bound for the latency: one minute is far past every client timeout in the lab. */
	public static final long MAX_LATENCY_MS = 60_000;

	/** A healthy gateway: no added latency. */
	public static final GatewayBehavior DEFAULT = new GatewayBehavior(0);

	public GatewayBehavior {
		if (latencyMs < 0 || latencyMs > MAX_LATENCY_MS) {
			throw new IllegalArgumentException(
					"Latency must be between 0 and " + MAX_LATENCY_MS + " ms, was " + latencyMs);
		}
	}

}
