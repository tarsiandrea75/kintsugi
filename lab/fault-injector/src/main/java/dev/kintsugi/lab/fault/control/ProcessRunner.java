package dev.kintsugi.lab.fault.control;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs one external program from an argv list, never through a shell. Injectable so that {@link ComposeRecreateControl}
 * is tested without Docker; {@link SystemProcessRunner} is the real one.
 */
public interface ProcessRunner {

	/**
	 * Runs {@code command} to completion, or kills it when it outlives {@link Command#timeout()}.
	 * @throws IOException the program could not be started
	 * @throws LabControlException the calling thread was interrupted while waiting; the program and its children were
	 * killed and the thread's interrupt flag is set again
	 */
	Result run(Command command) throws IOException;

	/**
	 * One program run.
	 * @param argv the program and its arguments, passed as they are
	 * @param environment the program's complete environment: nothing is inherited from the calling process, so the
	 * injector's own variables (its datasource credentials among them) never reach the program
	 * @param timeout how long the program may run before it is killed
	 */
	record Command(List<String> argv, Map<String, String> environment, Duration timeout) {

		public Command {
			argv = List.copyOf(argv);
			environment = Map.copyOf(environment);
		}

	}

	/**
	 * The outcome of a run. When {@code timedOut} is true the program was killed and {@code exitCode} is meaningless.
	 */
	record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

	}

}
