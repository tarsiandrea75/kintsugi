package dev.kintsugi.lab.fault.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a program with {@link ProcessBuilder} from an argv list, so no shell ever parses the arguments. The program gets
 * exactly {@link Command#environment()}, nothing inherited. Stdout and stderr go to temporary files rather than pipes: a
 * program that writes a lot cannot block on a full pipe, and nothing has to drain the streams concurrently.
 *
 * <p>
 * A program that outlives its timeout, or whose caller is interrupted while waiting, is killed together with its
 * children (the {@code docker} CLI runs {@code docker-compose} as a child process), so no recreate keeps running
 * unobserved after the injector has given up on it.
 */
public class SystemProcessRunner implements ProcessRunner {

	/** How long to wait for a killed process to exit before giving up on it. */
	private static final long KILL_GRACE_SECONDS = 5;

	@Override
	public Result run(Command command) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command.argv());
		Map<String, String> environment = builder.environment();
		environment.clear();
		environment.putAll(command.environment());

		Path stdout = Files.createTempFile("lab-control-", ".out");
		Path stderr = Files.createTempFile("lab-control-", ".err");
		try {
			builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
			Process process = builder.start();
			process.getOutputStream().close();
			boolean finished;
			try {
				finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException interrupted) {
				killTree(process);
				Thread.currentThread().interrupt();
				throw new LabControlException("interrupted while waiting for " + command.argv().get(0)
						+ "; the process and its children were killed", interrupted);
			}
			if (!finished) {
				killTree(process);
			}
			return new Result(finished ? process.exitValue() : -1, Files.readString(stdout, StandardCharsets.UTF_8),
					Files.readString(stderr, StandardCharsets.UTF_8), !finished);
		}
		finally {
			Files.deleteIfExists(stdout);
			Files.deleteIfExists(stderr);
		}
	}

	/**
	 * Kills {@code process} and every descendant, then waits briefly for them to exit. The descendants are listed before
	 * the parent dies: once it is gone they are re-parented and no longer reachable from it.
	 */
	private static void killTree(Process process) {
		List<ProcessHandle> descendants = process.descendants().toList();
		descendants.forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		try {
			process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
			for (ProcessHandle descendant : descendants) {
				descendant.onExit().get(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
			}
		}
		catch (InterruptedException again) {
			Thread.currentThread().interrupt();
		}
		catch (Exception notYetGone) {
			// Best effort: the kill signals are sent; a process that has not exited yet will not outlive SIGKILL.
		}
	}

}
