package dev.kintsugi.lab.fault.application;

import java.util.UUID;

/** A restore step failed; injection {@link #id()} stays blocking (500). */
public class RestoreFailedException extends RuntimeException {

	private final UUID id;

	public RestoreFailedException(UUID id, Throwable cause) {
		super("restore failed", cause);
		this.id = id;
	}

	public UUID id() {
		return id;
	}

}
