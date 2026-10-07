package dev.kintsugi.lab.fault.catalog;

/** The kind of true cause, stored in {@code injections.root_cause_category} and compared with the agent's diagnosis. */
public enum RootCauseCategory {

	DEPENDENCY_LATENCY, DEPENDENCY_REJECTING, CONFIGURATION, RESOURCE_EXHAUSTION

}
