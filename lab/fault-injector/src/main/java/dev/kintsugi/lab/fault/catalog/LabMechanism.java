package dev.kintsugi.lab.fault.catalog;

/**
 * How a catalog fault is applied to the lab and undone (ADR-009). Each mechanism has its own
 * {@link dev.kintsugi.lab.fault.control.LabControl} implementation.
 */
public enum LabMechanism {

	/** {@code PUT /admin/behavior} on gateway-mock with the fault's parameters; restored by {@code DELETE}. */
	GATEWAY_BEHAVIOR,

	/** A compose recreate of the target service with the fault's compose variables; restored without them. */
	COMPOSE_RECREATE

}
