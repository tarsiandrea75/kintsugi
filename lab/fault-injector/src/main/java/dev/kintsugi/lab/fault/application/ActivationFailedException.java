package dev.kintsugi.lab.fault.application;

import java.util.UUID;

/** An activation step failed; the outcome is recorded on injection {@link #id()} (500). */
public class ActivationFailedException extends RuntimeException {

	private final UUID id;

	public ActivationFailedException(UUID id, Throwable cause) {
		super("activation failed", cause);
		this.id = id;
	}

	public UUID id() {
		return id;
	}

}
