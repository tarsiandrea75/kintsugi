package dev.kintsugi.agent.incident;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The runner until the loop exists (T4 of odd/tasks/agent-loop.md): it does nothing, so the incident stays
 * {@code RUNNING}, and the next {@code POST /incidents} gets 409 until the agent restarts.
 */
@Component
@ConditionalOnWebApplication
class NoOpIncidentRunner implements IncidentRunner {

	private static final Logger log = LoggerFactory.getLogger(NoOpIncidentRunner.class);

	@Override
	public void start(UUID incidentId) {
		log.info("incident={} event=loop_not_implemented", incidentId);
	}

}
