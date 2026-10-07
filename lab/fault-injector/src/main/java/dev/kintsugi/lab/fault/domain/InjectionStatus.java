package dev.kintsugi.lab.fault.domain;

/**
 * Life of an injection. {@code ACTIVE} and {@code RESTORE_PENDING} are blocking: the lab is, or may still be, broken, so
 * no other fault may start (a partial unique index admits one blocking row at most).
 */
public enum InjectionStatus {

	/** The truth is recorded and the fault is being, or has been, applied. */
	ACTIVE,
	/** A restore failed, so the lab may still be broken. Cleared only by a successful restore. */
	RESTORE_PENDING,
	/** Restored on request. */
	RESTORED,
	/** The activation failed and its compensating restore succeeded. */
	FAILED;

	public boolean isBlocking() {
		return this == ACTIVE || this == RESTORE_PENDING;
	}

}
