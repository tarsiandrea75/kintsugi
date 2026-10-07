package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.kintsugi.lab.fault.control.ProcessRunner.Command;
import dev.kintsugi.lab.fault.control.ProcessRunner.Result;

/**
 * {@link SystemProcessRunner} with real, harmless processes: output capture, environment, exit code, timeout and
 * interruption.
 */
@EnabledOnOs({ OS.LINUX, OS.MAC })
class SystemProcessRunnerTest {

	private static final Map<String, String> PATH_ONLY = Map.of("PATH", System.getenv("PATH"));

	private final SystemProcessRunner runner = new SystemProcessRunner();

	@Test
	void capturesStdoutAndTheExitCode() throws Exception {
		Result result = runner.run(new Command(List.of("echo", "hello"), Map.of(), Duration.ofSeconds(10)));

		assertThat(result.exitCode()).isZero();
		assertThat(result.timedOut()).isFalse();
		assertThat(result.stdout()).isEqualTo("hello\n");
	}

	@Test
	void capturesStderrOfANonZeroExit() throws Exception {
		Result result = runner.run(new Command(List.of("ls", "/no/such/path/kintsugi"), Map.of(), Duration.ofSeconds(10)));

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.stderr()).contains("/no/such/path/kintsugi");
	}

	@Test
	void runsWithExactlyTheGivenEnvironmentAndInheritsNothing() throws Exception {
		Result result = runner.run(new Command(List.of("env"), Map.of("KINTSUGI_TEST_VAR", "on"), Duration.ofSeconds(10)));

		// The JVM's own environment (PATH, HOME, any datasource credential) never reaches the child.
		assertThat(result.stdout().lines()).containsExactly("KINTSUGI_TEST_VAR=on");
	}

	@Test
	void killsAProcessThatOutlivesItsTimeout() throws Exception {
		long started = System.nanoTime();
		Result result = runner.run(new Command(List.of("sleep", "30"), PATH_ONLY, Duration.ofMillis(300)));

		assertThat(result.timedOut()).isTrue();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
	}

	/**
	 * A shell that starts {@code sleep 30} as its child, the shape of {@code docker} running {@code docker-compose}.
	 * Interrupting the waiting thread must kill both, restore the interrupt flag and report a failed step.
	 */
	@Test
	void anInterruptKillsTheProcessAndItsChildren(@TempDir Path dir) throws Exception {
		Path pids = dir.resolve("pids");
		Command command = new Command(
				List.of("sh", "-c", "echo $$ > \"$0.tmp\"; sleep 30 & echo $! >> \"$0.tmp\"; mv \"$0.tmp\" \"$0\"; wait",
						pids.toString()),
				PATH_ONLY, Duration.ofSeconds(60));
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interruptedAfter = new AtomicBoolean();
		Thread caller = new Thread(() -> {
			try {
				runner.run(command);
			}
			catch (Throwable failure) {
				thrown.set(failure);
			}
			interruptedAfter.set(Thread.currentThread().isInterrupted());
		});
		caller.start();
		List<Long> started = awaitPids(pids);

		caller.interrupt();
		caller.join(Duration.ofSeconds(10));

		assertThat(caller.isAlive()).isFalse();
		assertThat(thrown.get()).isInstanceOf(ProcessInterruptedException.class).hasMessageContaining("interrupted");
		assertThat(interruptedAfter).isTrue();
		assertThat(started).hasSize(2).allSatisfy(pid -> assertThat(awaitGone(pid)).as("pid %d is gone", pid).isTrue());
	}

	private static List<Long> awaitPids(Path pids) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!Files.exists(pids)) {
			assertThat(System.nanoTime()).as("the shell wrote its pids").isLessThan(deadline);
			Thread.sleep(20);
		}
		return Files.readAllLines(pids).stream().map(String::strip).map(Long::valueOf).toList();
	}

	private static boolean awaitGone(long pid) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				Thread.sleep(50);
			}
			else {
				return true;
			}
		}
		return false;
	}

}
