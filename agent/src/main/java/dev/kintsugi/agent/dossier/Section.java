package dev.kintsugi.agent.dossier;

/**
 * The part of the dossier a numbered line states a fact of (ADR-028, Decision 3): the header, or one of the sections
 * both views write, whatever the view groups them by.
 */
public enum Section {

	/** The header's lines, a section's introductory line, and a sheet's place in the call graph. */
	HEADER,

	/** Restarts and uptime, from {@code service_restarts}. */
	STATUS,

	/** Order and payment states, from {@code status_summary}. */
	STATES,

	/** Request counts per edge and side, from {@code traffic_counts}. */
	TRAFFIC,

	/** Server-side latency, from {@code query_metric latency_avg}. */
	LATENCY,

	/** Resource series, from {@code query_metric}. */
	RESOURCES,

	/** Log counts and signatures, incident window only. */
	LOGS,

	/** Event counts, from {@code event_counts}. */
	EVENTS

}
