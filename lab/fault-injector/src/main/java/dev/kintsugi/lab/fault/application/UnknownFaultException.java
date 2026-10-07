package dev.kintsugi.lab.fault.application;

/** The requested fault id is not in the catalog (400). */
public class UnknownFaultException extends RuntimeException {

	public UnknownFaultException() {
		super("unknown fault");
	}

}
