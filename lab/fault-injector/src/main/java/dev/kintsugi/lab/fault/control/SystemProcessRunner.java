package dev.kintsugi.lab.fault.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a program with {@link ProcessBuilder} from an argv list, so no shell ever parses the arguments. Stdout and stderr
 * go to temporary files rather than pipes: a program that writes a lot cannot block on a full pipe, and nothing has to
 * drain the streams concurrently. A program that outlives its timeout is killed together with its children (the
 * {@code docker} CLI runs {@code docker-compose} as a child process).
 */
public class SystemProcessRunner implements ProcessRunner {

	/** How long to wait for a killed process to exit before giving up on it. */
	private static final long KILL_GRACE_SECONDS = 5;

	@Override
	public Result run(Command command) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command.argv());
		Map<String, String> environment = builder.environment();
		command.unsetEnv().forEach(environment::remove);
		environment.putAll(command.setEnv());

		Path stdout = Files.createTempFile("lab-control-", ".out");
		Path stderr = Files.createTempFile("lab-control-", ".err");
		try {
			builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
			Process process = builder.start();
			process.getOutputStream().close();
			boolean finished = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
			if (!finished) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
			}
			return new Result(finished ? process.exitValue() : -1, Files.readString(stdout, StandardCharsets.UTF_8),
					Files.readString(stderr, StandardCharsets.UTF_8), !finished);
		}
		finally {
			Files.deleteIfExists(stdout);
			Files.deleteIfExists(stderr);
		}
	}

}
