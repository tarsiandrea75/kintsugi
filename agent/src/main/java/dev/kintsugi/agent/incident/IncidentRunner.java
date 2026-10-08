package dev.kintsugi.agent.incident;

import java.util.UUID;

/**
 * Runs the loop of an incident that {@code POST /incidents} has just stored as {@code RUNNING}. It must return at once
 * and run the loop in the background: the API answers 202 without waiting for it.
 */
public interface IncidentRunner {

	void start(UUID incidentId);

}
