package dev.kintsugi.lab.fault.catalog;

/** A requested parameter is not one the fault declares, or its value is not one of the declared values (400). */
public class ParameterRefusedException extends RuntimeException {

	/** Why the parameter was refused. */
	public enum Reason {

		/** The fault declares no parameter of that name: an unknown name, or a fault (F3, F4) that declares none. */
		UNDECLARED_PARAMETER,

		/** The name is declared, but the value is not one of its declared values. */
		UNDECLARED_VALUE

	}

	private final Reason reason;

	public ParameterRefusedException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

}
