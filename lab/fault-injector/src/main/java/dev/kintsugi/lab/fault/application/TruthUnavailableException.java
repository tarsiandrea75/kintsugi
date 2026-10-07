package dev.kintsugi.lab.fault.application;

/** The truth could not be recorded, so nothing was injected (503). */
public class TruthUnavailableException extends RuntimeException {

	public TruthUnavailableException(Throwable cause) {
		super("the faults database is unreachable", cause);
	}

}
