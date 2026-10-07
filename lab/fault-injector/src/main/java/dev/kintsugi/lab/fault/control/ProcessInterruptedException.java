package dev.kintsugi.lab.fault.control;

/**
 * The thread waiting for a lab-control process was interrupted; the process tree was killed and the interrupt flag is
 * set again. A dedicated type, so callers can tell an interrupt apart from any other failed step.
 */
public class ProcessInterruptedException extends LabControlException {

	public ProcessInterruptedException(String message, Throwable cause) {
		super(message, cause);
	}

}
