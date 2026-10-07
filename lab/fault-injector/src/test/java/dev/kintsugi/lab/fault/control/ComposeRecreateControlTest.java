package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.control.ProcessRunner.Command;
import dev.kintsugi.lab.fault.control.ProcessRunner.Result;

/**
 * {@link ComposeRecreateControl} with a recording process runner: the exact argv, the minimal environment, the failure
 * mapping and the per-service serialization.
 */
class ComposeRecreateControlTest {

	private static final Path COMPOSE_FILE = Path.of("/home/someone/kintsugi/infra/compose.yaml");

	/** Records every command and answers with a scripted result. */
	private static final class RecordingRunner implements ProcessRunner {

		final List<Command> commands = new ArrayList<>();

		Result answer = new Result(0, "", "", false);

		IOException failure;

		RuntimeException interruption;

		@Override
		public Result run(Command command) throws IOException {
			commands.add(command);
			if (failure != null) {
				throw failure;
			}
			if (interruption != null) {
				throw interruption;
			}
			return answer;
		}

	}

	private final RecordingRunner runner = new RecordingRunner();

	/**
	 * The injector's own environment: what the CLI needs, plus what it must never see (the injector's datasource
	 * credentials) and a stale fault variable that a restore must not pick up.
	 */
	private static final Map<String, String> HOST_ENVIRONMENT = Map.of("PATH", "/usr/local/bin:/usr/bin:/bin", "HOME",
			"/home/app", "DOCKER_HOST", "unix:///var/run/docker.sock", "DOCKER_CONFIG", "/tmp/.docker", "DOCKER_GID",
			"1001", "SPRING_DATASOURCE_USERNAME", "faults", "SPRING_DATASOURCE_PASSWORD", "secret", "ORDER_PAYMENT_BASEURL",
			"http://stale:1");

	/**
	 * What the CLI gets. DOCKER_GID is there because compose interpolates the whole file for any command, and the file
	 * requires it for the injector's own service.
	 */
	private static final Map<String, String> CLI_ENVIRONMENT = Map.of("PATH", "/usr/local/bin:/usr/bin:/bin", "HOME",
			"/home/app", "DOCKER_HOST", "unix:///var/run/docker.sock", "DOCKER_CONFIG", "/tmp/.docker", "DOCKER_GID",
			"1001");

	private final ComposeRecreateControl control = new ComposeRecreateControl(runner, COMPOSE_FILE, "kintsugi-lab",
			Duration.ofSeconds(120), HOST_ENVIRONMENT);

	@Test
	void f3RecreatesOrderServiceWithTheWrongPaymentUrl() {
		control.activate(Fault.F3);

		Command command = runner.commands.get(0);
		assertThat(command.argv()).containsExactly("docker", "compose", "-p", "kintsugi-lab", "-f",
				"/home/someone/kintsugi/infra/compose.yaml", "up", "-d", "--no-deps", "--no-build", "--wait",
				"--wait-timeout", "120", "order-service");
		assertThat(command.environment()).containsAllEntriesOf(CLI_ENVIRONMENT)
			.containsEntry("ORDER_PAYMENT_BASEURL", "http://payment-service:9999")
			.hasSize(CLI_ENVIRONMENT.size() + 1);
		assertThat(command.timeout()).isEqualTo(Duration.ofSeconds(120).plus(ComposeRecreateControl.PROCESS_MARGIN));
	}

	@Test
	void f4RecreatesInventoryServiceWithASmallPoolAndADelay() {
		control.activate(Fault.F4);

		Command command = runner.commands.get(0);
		assertThat(command.argv()).last().isEqualTo("inventory-service");
		assertThat(command.environment()).containsEntry("INVENTORY_POOL_SIZE", "2")
			.containsEntry("INVENTORY_RESERVATION_DELAY", "5s");
	}

	@Test
	void restoreRunsTheSameCommandWithTheFaultVariablesRemoved() {
		control.activate(Fault.F4);
		control.restore(Fault.F4);

		Command activation = runner.commands.get(0);
		Command restore = runner.commands.get(1);
		assertThat(restore.argv()).isEqualTo(activation.argv());
		assertThat(restore.environment()).isEqualTo(CLI_ENVIRONMENT);
	}

	@Test
	void theCliGetsAMinimalEnvironmentWithoutTheInjectorsCredentials() {
		control.restore(Fault.F3);

		// Only PATH, HOME, DOCKER_HOST, DOCKER_CONFIG and DOCKER_GID are passed through: no datasource credential, and no stale
		// fault variable that would make a restore re-apply the fault.
		assertThat(runner.commands.get(0).environment()).isEqualTo(CLI_ENVIRONMENT);
	}

	@Test
	void anEmptyOrUnsetDockerVariableIsNotPassed() {
		// DOCKER_HOST is set but empty, DOCKER_CONFIG is not set at all: neither reaches the CLI.
		ComposeRecreateControl withoutDockerHost = new ComposeRecreateControl(runner, COMPOSE_FILE, "kintsugi-lab",
				Duration.ofSeconds(120), Map.of("PATH", "/usr/bin", "HOME", "/home/app", "DOCKER_HOST", ""));

		withoutDockerHost.restore(Fault.F3);

		assertThat(runner.commands.get(0).environment()).isEqualTo(Map.of("PATH", "/usr/bin", "HOME", "/home/app"));
	}

	/**
	 * Two overlapping restores of the same service must not run {@code up} concurrently: the second waits until the
	 * first has finished. A recreate of another service is not held up.
	 */
	@Test
	void recreatesOfTheSameServiceAreSerialized() throws Exception {
		BlockingRunner blocking = new BlockingRunner();
		ComposeRecreateControl serialized = new ComposeRecreateControl(blocking, COMPOSE_FILE, "kintsugi-lab",
				Duration.ofSeconds(120), HOST_ENVIRONMENT);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			Future<?> first = pool.submit(() -> serialized.restore(Fault.F3));
			assertThat(blocking.entered.await(5, TimeUnit.SECONDS)).isTrue();
			Future<?> second = pool.submit(() -> serialized.restore(Fault.F3));
			Future<?> otherService = pool.submit(() -> serialized.restore(Fault.F4));

			// The other service gets through while order-service is held; the second order-service restore does not.
			otherService.get(5, TimeUnit.SECONDS);
			Thread.sleep(300);
			assertThat(blocking.servicesRun()).containsExactly("order-service", "inventory-service");

			blocking.release.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
			assertThat(blocking.servicesRun()).containsExactly("order-service", "inventory-service", "order-service");
			assertThat(blocking.maxConcurrentPerService.get()).isEqualTo(1);
		}
		finally {
			blocking.release.countDown();
			pool.shutdownNow();
		}
	}

	/** Holds the first order-service run until released, and tracks how many runs of one service overlap. */
	private static final class BlockingRunner implements ProcessRunner {

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		final AtomicInteger maxConcurrentPerService = new AtomicInteger();

		private final List<String> services = new CopyOnWriteArrayList<>();

		private final Map<String, AtomicInteger> running = new ConcurrentHashMap<>();

		private final AtomicBoolean held = new AtomicBoolean();

		@Override
		public Result run(Command command) {
			String service = command.argv().get(command.argv().size() - 1);
			services.add(service);
			int concurrent = running.computeIfAbsent(service, s -> new AtomicInteger()).incrementAndGet();
			maxConcurrentPerService.accumulateAndGet(concurrent, Math::max);
			try {
				if (service.equals("order-service") && held.compareAndSet(false, true)) {
					entered.countDown();
					release.await(10, TimeUnit.SECONDS);
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			finally {
				running.get(service).decrementAndGet();
			}
			return new Result(0, "", "", false);
		}

		List<String> servicesRun() {
			return List.copyOf(services);
		}

	}

	@Test
	void aNonZeroExitIsAFailedStepWithTheTailOfStderr() {
		runner.answer = new Result(1, "", "x".repeat(5_000) + "\nservice \"order-service\" is unhealthy", false);

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F3))
			.withMessageContaining("exit 1")
			.withMessageEndingWith("service \"order-service\" is unhealthy")
			.satisfies(failure -> assertThat(failure.getMessage()).hasSizeLessThan(1_000));
	}

	@Test
	void aTimeoutIsAFailedStep() {
		runner.answer = new Result(-1, "", "waiting for order-service", true);

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.restore(Fault.F3))
			.withMessageContaining("timed out")
			.withMessageContaining("waiting for order-service");
	}

	@Test
	void aProcessThatCannotStartIsAFailedStep() {
		runner.failure = new IOException("Cannot run program \"docker\"");

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F3))
			.withMessageContaining("Cannot run program");
	}

	@Test
	void anInterruptedRunNamesTheStepAndTheFaultAndKeepsTheCause() {
		LabControlException interrupted = new LabControlException(
				"interrupted while waiting for docker; the process and its children were killed",
				new InterruptedException());
		runner.interruption = interrupted;

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.restore(Fault.F3))
			.withMessage("compose restore of F3 was interrupted: interrupted while waiting for docker; the process and"
					+ " its children were killed")
			.withCause(interrupted);
	}

	/**
	 * A recreate interrupted while another recreate of the same service holds the lock gives up without running
	 * anything, and keeps the thread's interrupt flag set.
	 */
	@Test
	void anInterruptWhileWaitingForTheServiceLockRunsNothing() throws Exception {
		BlockingRunner blocking = new BlockingRunner();
		ComposeRecreateControl serialized = new ComposeRecreateControl(blocking, COMPOSE_FILE, "kintsugi-lab",
				Duration.ofSeconds(120), HOST_ENVIRONMENT);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = pool.submit(() -> serialized.restore(Fault.F3));
			assertThat(blocking.entered.await(5, TimeUnit.SECONDS)).isTrue();

			Thread.currentThread().interrupt();
			try {
				assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> serialized.restore(Fault.F3))
					.withMessageContaining("compose restore of F3 was interrupted while waiting")
					.withCauseInstanceOf(InterruptedException.class);
			}
			finally {
				assertThat(Thread.interrupted()).isTrue();
			}
			assertThat(blocking.servicesRun()).containsExactly("order-service");

			blocking.release.countDown();
			holder.get(5, TimeUnit.SECONDS);
			assertThat(blocking.servicesRun()).containsExactly("order-service");
		}
		finally {
			blocking.release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void aGatewayFaultIsRefusedWithoutRunningAnything() {
		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F1));
		assertThat(runner.commands).isEmpty();
	}

	@Test
	void theWaitTimeoutMustBeAtLeastOneSecond() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
				() -> new ComposeRecreateControl(runner, COMPOSE_FILE, "kintsugi-lab", Duration.ofMillis(500),
						HOST_ENVIRONMENT));
	}

}
