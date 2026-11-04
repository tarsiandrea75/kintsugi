package dev.kintsugi.agent.dossier;

/**
 * Whether a numbered line's fact changed between the previous and the incident window, by its section's rule
 * ({@link WindowChange}; ADR-028, Decision 5).
 */
public enum Change {

	/** The section's rule holds: the fact changed. */
	YES,

	/** Both windows were compared and the section's rule does not hold. */
	NO,

	/**
	 * Nothing was compared: a header line, a log line (gathered for the incident window only), a failed call, a line
	 * with no value, or a window too sparse for the section's rule. It never counts as changed.
	 */
	NOT_COMPARED

}
