package dev.kintsugi.agent.incident;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Fails every incident left {@code RUNNING} by a previous agent process (agent spec, "Agent restarted during a run").
 * No loop survives a restart, so such an incident would stay {@code RUNNING} forever and, through the one-{@code RUNNING}
 * index, block every new one. It runs once the singletons exist, after Flyway and before the web server accepts a
 * request, so no {@code POST} can race it.
 */
@Component
@ConditionalOnWebApplication
class RestartRecovery implements SmartInitializingSingleton {

	static final String REASON = "agent restarted during the run";

	private static final Logger log = LoggerFactory.getLogger(RestartRecovery.class);

	private final IncidentRepository incidents;

	private final Clock clock;

	RestartRecovery(IncidentRepository incidents, Clock clock) {
		this.incidents = incidents;
		this.clock = clock;
	}

	@Override
	public void afterSingletonsInstantiated() {
		int failed = incidents.failRunning(REASON, clock.instant());
		if (failed > 0) {
			log.warn("event=incidents_failed_on_restart count={} reason=\"{}\"", failed, REASON);
		}
	}

}
