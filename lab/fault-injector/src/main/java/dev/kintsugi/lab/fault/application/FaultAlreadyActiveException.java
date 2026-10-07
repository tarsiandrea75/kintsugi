package dev.kintsugi.lab.fault.application;

/** Another injection is blocking, so a second fault may not start (409). */
public class FaultAlreadyActiveException extends RuntimeException {

	public FaultAlreadyActiveException() {
		super("a fault is already active");
	}

}
