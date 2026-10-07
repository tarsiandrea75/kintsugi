package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import dev.kintsugi.lab.fault.control.ProcessRunner.Command;
import dev.kintsugi.lab.fault.control.ProcessRunner.Result;

/** {@link SystemProcessRunner} with real, harmless processes: output capture, environment, exit code and timeout. */
@EnabledOnOs({ OS.LINUX, OS.MAC })
class SystemProcessRunnerTest {

	private final SystemProcessRunner runner = new SystemProcessRunner();

	@Test
	void capturesStdoutAndTheExitCode() throws Exception {
		Result result = runner.run(new Command(List.of("echo", "hello"), Map.of(), Set.of(), Duration.ofSeconds(10)));

		assertThat(result.exitCode()).isZero();
		assertThat(result.timedOut()).isFalse();
		assertThat(result.stdout()).isEqualTo("hello\n");
	}

	@Test
	void capturesStderrOfANonZeroExit() throws Exception {
		Result result = runner.run(new Command(List.of("ls", "/no/such/path/kintsugi"), Map.of(), Set.of(),
				Duration.ofSeconds(10)));

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.stderr()).contains("/no/such/path/kintsugi");
	}

	@Test
	void setsAndRemovesEnvironmentVariables() throws Exception {
		Result set = runner.run(new Command(List.of("env"), Map.of("KINTSUGI_TEST_VAR", "on"), Set.of(),
				Duration.ofSeconds(10)));
		Result unset = runner.run(new Command(List.of("env"), Map.of(), Set.of("PATH"), Duration.ofSeconds(10)));

		assertThat(set.stdout().lines()).contains("KINTSUGI_TEST_VAR=on");
		// Whole lines: MANPATH or LD_LIBRARY_PATH would contain "PATH=" too.
		assertThat(unset.stdout().lines()).noneMatch(line -> line.startsWith("PATH="));
	}

	@Test
	void killsAProcessThatOutlivesItsTimeout() throws Exception {
		long started = System.nanoTime();
		Result result = runner.run(new Command(List.of("sleep", "30"), Map.of(), Set.of(), Duration.ofMillis(300)));

		assertThat(result.timedOut()).isTrue();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
	}

}
