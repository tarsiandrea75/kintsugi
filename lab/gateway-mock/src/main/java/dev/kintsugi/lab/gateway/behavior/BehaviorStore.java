package dev.kintsugi.lab.gateway.behavior;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the current {@link GatewayBehavior} in memory. Request threads read it while the admin API replaces it, so
 * the reference is atomic and the behavior itself is immutable.
 *
 * <p>Every change is logged at {@code INFO}: the fault injector and the observability stack read these lines to see
 * when the lab was broken and when it was restored.
 */
@Component
public class BehaviorStore {

	private static final Logger log = LoggerFactory.getLogger(BehaviorStore.class);

	private final AtomicReference<GatewayBehavior> current = new AtomicReference<>(GatewayBehavior.DEFAULT);

	public GatewayBehavior get() {
		return current.get();
	}

	/** Replaces the behavior with {@code behavior} and returns it. */
	public GatewayBehavior set(GatewayBehavior behavior) {
		GatewayBehavior previous = current.getAndSet(behavior);
		log.info("Gateway behavior changed: latencyMs {} -> {}, errorStatus {} -> {}", previous.latencyMs(),
				behavior.latencyMs(), describe(previous.errorStatus()), describe(behavior.errorStatus()));
		return behavior;
	}

	/** Restores {@link GatewayBehavior#DEFAULT} and returns it. */
	public GatewayBehavior reset() {
		GatewayBehavior previous = current.getAndSet(GatewayBehavior.DEFAULT);
		log.info("Gateway behavior reset: latencyMs {} -> {}, errorStatus {} -> {}", previous.latencyMs(),
				GatewayBehavior.DEFAULT.latencyMs(), describe(previous.errorStatus()),
				describe(GatewayBehavior.DEFAULT.errorStatus()));
		return GatewayBehavior.DEFAULT;
	}

	/** Renders a missing error status as {@code none}, which reads better in a log line than {@code null}. */
	private static String describe(Integer errorStatus) {
		return errorStatus == null ? "none" : errorStatus.toString();
	}

}
