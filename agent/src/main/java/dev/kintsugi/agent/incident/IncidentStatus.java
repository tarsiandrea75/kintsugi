package dev.kintsugi.agent.incident;

/** The statuses of an incident (agent spec, "Statuses"); the {@code incidents.status} CHECK lists the same values. */
public enum IncidentStatus {

	RUNNING, COMPLETED, INVALID_REPORT, BUDGET_EXCEEDED, TIMED_OUT, FAILED

}
