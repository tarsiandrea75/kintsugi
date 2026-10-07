package dev.kintsugi.lab.fault.control;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.catalog.LabMechanism;
import dev.kintsugi.lab.fault.control.ProcessRunner.Command;
import dev.kintsugi.lab.fault.control.ProcessRunner.Result;

/**
 * Applies F3 and F4 as a real redeploy (ADR-009): recreates the target service with the fault's compose variables set,
 * and restores it with the same command and those variables removed, so compose falls back to the defaults in
 * {@code infra/compose.yaml}.
 *
 * <pre>
 * docker compose -p &lt;project&gt; -f &lt;compose file&gt; up -d --no-deps --no-build --wait --wait-timeout &lt;N&gt; &lt;service&gt;
 * </pre>
 *
 * The command is an argv list, never a shell line. A non-zero exit, a {@code --wait-timeout} expiry or a process that
 * outlives {@code N} plus {@link #PROCESS_MARGIN} is a failed step: {@link LabControlException} with the tail of stderr.
 */
public class ComposeRecreateControl implements LabControl {

	/** Extra time the CLI gets beyond {@code --wait-timeout} (pulling nothing, stopping the old container) before a kill. */
	static final Duration PROCESS_MARGIN = Duration.ofSeconds(30);

	/** How much of stderr a failure message keeps: compose prints the reason last. */
	private static final int STDERR_TAIL_CHARS = 600;

	private static final Logger log = LoggerFactory.getLogger(ComposeRecreateControl.class);

	private final ProcessRunner runner;

	private final Path composeFile;

	private final String project;

	private final Duration waitTimeout;

	public ComposeRecreateControl(ProcessRunner runner, Path composeFile, String project, Duration waitTimeout) {
		if (waitTimeout.toSeconds() < 1) {
			throw new IllegalArgumentException("kintsugi.lab-control.recreate-wait-timeout must be at least 1s");
		}
		this.runner = runner;
		this.composeFile = composeFile;
		this.project = project;
		this.waitTimeout = waitTimeout;
	}

	@Override
	public void activate(Fault fault) {
		run(fault, "activate", composeVariables(fault), Set.of());
	}

	@Override
	public void restore(Fault fault) {
		run(fault, "restore", Map.of(), composeVariables(fault).keySet());
	}

	private void run(Fault fault, String step, Map<String, String> setEnv, Set<String> unsetEnv) {
		if (fault.mechanism() != LabMechanism.COMPOSE_RECREATE) {
			throw new LabControlException(fault.id() + " is not applied by a compose recreate");
		}
		Command command = new Command(argv(fault.targetService()), setEnv, unsetEnv, waitTimeout.plus(PROCESS_MARGIN));
		Result result;
		try {
			result = runner.run(command);
		}
		catch (IOException notStarted) {
			throw new LabControlException("compose " + step + " of " + fault.id() + " could not start: "
					+ notStarted.getMessage(), notStarted);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new LabControlException("compose " + step + " of " + fault.id() + " was interrupted", interrupted);
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
