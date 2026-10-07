package dev.kintsugi.lab.fault.control;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one external program from an argv list, never through a shell. Injectable so that {@link ComposeRecreateControl}
 * is tested without Docker; {@link SystemProcessRunner} is the real one.
 */
public interface ProcessRunner {

	/**
	 * Runs {@code command} to completion, or kills it when it outlives {@link Command#timeout()}.
	 * @throws IOException the program could not be started
	 * @throws InterruptedException the calling thread was interrupted while waiting
	 */
	Result run(Command command) throws IOException, InterruptedException;

	/**
	 * One program run.
	 * @param argv the program and its arguments, passed as they are
	 * @param setEnv variables added to the inherited environment
	 * @param unsetEnv variables removed from the inherited environment (applied before {@code setEnv})
	 * @param timeout how long the program may run before it is killed
	 */
	record Command(List<String> argv, Map<String, String> setEnv, Set<String> unsetEnv, Duration timeout) {

		public Command {
			argv = List.copyOf(argv);
			setEnv = Map.copyOf(setEnv);
			unsetEnv = Set.copyOf(unsetEnv);
		}

	}

	/**
	 * The outcome of a run. When {@code timedOut} is true the program was killed and {@code exitCode} is meaningless.
	 */
	record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

	}

}
