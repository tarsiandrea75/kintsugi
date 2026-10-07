package dev.kintsugi.lab.fault.control;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.catalog.LabMechanism;
import dev.kintsugi.lab.fault.control.ProcessRunner.Command;
import dev.kintsugi.lab.fault.control.ProcessRunner.Result;

/**
 * Applies F3 and F4 as a real redeploy (ADR-009): recreates the target service with the fault's compose variables set,
 * and restores it with the same command without those variables, so compose falls back to the defaults in
 * {@code infra/compose.yaml}.
 *
 * <pre>
 * docker compose -p &lt;project&gt; -f &lt;compose file&gt; up -d --no-deps --no-build --wait --wait-timeout &lt;N&gt; &lt;service&gt;
 * </pre>
 *
 * <ul>
 * <li>The command is an argv list, never a shell line. {@code --no-deps} plus the one explicit service name means compose
 * recreates that service only: never its dependencies, never the injector itself, never the whole stack.</li>
 * <li>The CLI gets a minimal environment: {@link #PASSED_THROUGH} from the injector's own environment, plus the fault
 * variables on activation. The injector's datasource credentials never reach it, and a restore never inherits a fault
 * variable.</li>
 * <li>Recreates of one service are serialized: two overlapping restores never run {@code up} on the same service at
 * once. Different services do not wait for each other.</li>
 * <li>A non-zero exit, a {@code --wait-timeout} expiry or a process that outlives {@code N} plus {@link #PROCESS_MARGIN}
 * is a failed step: {@link LabControlException} with the tail of stderr. So is an interrupt, which kills the CLI;
 * every message names the step and the fault.</li>
 * </ul>
 */
public class ComposeRecreateControl implements LabControl {

	/** Extra time the CLI gets beyond {@code --wait-timeout} (pulling nothing, stopping the old container) before a kill. */
	static final Duration PROCESS_MARGIN = Duration.ofSeconds(30);

	/**
	 * The only variables of the injector's environment the CLI sees, when set: the program search path, the home
	 * directory, the daemon address, the CLI's config directory (the image points it at a writable place, because the
	 * {@code app} user has no home), and {@code DOCKER_GID}: compose interpolates the whole file for any command, and
	 * {@code infra/compose.yaml} requires it for the injector's own service.
	 */
	static final List<String> PASSED_THROUGH = List.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_GID");

	/** How much of stderr a failure message keeps: compose prints the reason last. */
	private static final int STDERR_TAIL_CHARS = 600;

	private static final Logger log = LoggerFactory.getLogger(ComposeRecreateControl.class);

	private final ProcessRunner runner;

	private final Path composeFile;

	private final String project;

	private final Duration waitTimeout;

	private final Map<String, String> baseEnvironment;

	private final Map<String, ReentrantLock> serviceLocks = new ConcurrentHashMap<>();

	/**
	 * @param hostEnvironment the injector's own environment ({@link System#getenv()}); only {@link #PASSED_THROUGH} is
	 * kept from it
	 */
	public ComposeRecreateControl(ProcessRunner runner, Path composeFile, String project, Duration waitTimeout,
			Map<String, String> hostEnvironment) {
		if (waitTimeout.toSeconds() < 1) {
			throw new IllegalArgumentException("kintsugi.lab-control.recreate-wait-timeout must be at least 1s");
		}
		this.runner = runner;
		this.composeFile = composeFile;
		this.project = project;
		this.waitTimeout = waitTimeout;
		Map<String, String> base = new LinkedHashMap<>();
		for (String name : PASSED_THROUGH) {
			String value = hostEnvironment.get(name);
			if (value != null && !value.isEmpty()) {
				base.put(name, value);
			}
		}
		this.baseEnvironment = Map.copyOf(base);
	}

	@Override
	public void activate(Fault fault) {
		run(fault, "activate", composeVariables(fault));
	}

	@Override
	public void restore(Fault fault) {
		run(fault, "restore", Map.of());
	}

	private void run(Fault fault, String step, Map<String, String> faultVariables) {
		if (fault.mechanism() != LabMechanism.COMPOSE_RECREATE) {
			throw new LabControlException(fault.id() + " is not applied by a compose recreate");
		}
		Map<String, String> environment = new LinkedHashMap<>(baseEnvironment);
		environment.putAll(faultVariables);
		Command command = new Command(argv(fault.targetService()), environment, waitTimeout.plus(PROCESS_MARGIN));
		ReentrantLock lock = serviceLocks.computeIfAbsent(fault.targetService(), service -> new ReentrantLock());
		Result result;
		try {
			lock.lockInterruptibly();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new LabControlException("compose " + step + " of " + fault.id()
					+ " was interrupted while waiting for another recreate of the same service", interrupted);
		}
		try {
			result = runner.run(command);
		}
		catch (IOException notStarted) {
			throw new LabControlException("compose " + step + " of " + fault.id() + " could not start: "
					+ notStarted.getMessage(), notStarted);
		}
		catch (ProcessInterruptedException interrupted) {
			// The runner killed the process tree and set the interrupt flag again; add the step and fault for the record.
			throw new LabControlException("compose " + step + " of " + fault.id() + " was interrupted: "
					+ interrupted.getMessage(), interrupted);
		}
		finally {
			lock.unlock();
		}
		if (result.timedOut()) {
			throw new LabControlException("compose " + step + " of " + fault.id() + " timed out after "
					+ command.timeout().toSeconds() + "s and was killed; stderr: " + tail(result.stderr()));
		}
		if (result.exitCode() != 0) {
			throw new LabControlException("compose " + step + " of " + fault.id() + " failed with exit "
					+ result.exitCode() + "; stderr: " + tail(result.stderr()));
		}
		log.info("event=compose_recreate_done step={} fault={}", step, fault.id());
	}

	private List<String> argv(String service) {
		return List.of("docker", "compose", "-p", project, "-f", composeFile.toString(), "up", "-d", "--no-deps",
				"--no-build", "--wait", "--wait-timeout", Long.toString(waitTimeout.toSeconds()), service);
	}

	/** The fault's catalog parameters, which for a recreate are compose variables (ADR-009, naming table). */
	private static Map<String, String> composeVariables(Fault fault) {
		Map<String, String> variables = new LinkedHashMap<>();
		fault.parameters().forEach((name, value) -> variables.put(name, String.valueOf(value)));
		return variables;
	}

	private static String tail(String stderr) {
		String trimmed = stderr.strip();
		return trimmed.length() <= STDERR_TAIL_CHARS ? trimmed
				: "..." + trimmed.substring(trimmed.length() - STDERR_TAIL_CHARS);
	}

}
