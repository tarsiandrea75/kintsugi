package dev.kintsugi.lab.fault.control;

/** A lab activation or restore step failed. Its message is recorded in {@code injection_events}, never returned. */
public class LabControlException extends RuntimeException {

	public LabControlException(String message) {
		super(message);
	}

	public LabControlException(String message, Throwable cause) {
		super(message, cause);
	}

}
