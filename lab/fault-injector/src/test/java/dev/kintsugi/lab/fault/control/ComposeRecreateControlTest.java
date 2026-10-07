package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.control.ProcessRunner.Command;
import dev.kintsugi.lab.fault.control.ProcessRunner.Result;

/** {@link ComposeRecreateControl} with a recording process runner: the exact argv, environment and failure mapping. */
class ComposeRecreateControlTest {

	private static final Path COMPOSE_FILE = Path.of("/home/someone/kintsugi/infra/compose.yaml");

	/** Records every command and answers with a scripted result. */
	private static final class RecordingRunner implements ProcessRunner {

		final List<Command> commands = new ArrayList<>();

		Result answer = new Result(0, "", "", false);

		IOException failure;

		@Override
		public Result run(Command command) throws IOException {
			commands.add(command);
			if (failure != null) {
				throw failure;
			}
			return answer;
		}

	}

	private final RecordingRunner runner = new RecordingRunner();

	private final ComposeRecreateControl control = new ComposeRecreateControl(runner, COMPOSE_FILE, "kintsugi-lab",
			Duration.ofSeconds(120));

	@Test
	void f3RecreatesOrderServiceWithTheWrongPaymentUrl() {
		control.activate(Fault.F3);

		Command command = runner.commands.get(0);
		assertThat(command.argv()).containsExactly("docker", "compose", "-p", "kintsugi-lab", "-f",
				"/home/someone/kintsugi/infra/compose.yaml", "up", "-d", "--no-deps", "--no-build", "--wait",
				"--wait-timeout", "120", "order-service");
		assertThat(command.setEnv()).isEqualTo(Map.of("ORDER_PAYMENT_BASEURL", "http://payment-service:9999"));
		assertThat(command.unsetEnv()).isEmpty();
		assertThat(command.timeout()).isEqualTo(Duration.ofSeconds(120).plus(ComposeRecreateControl.PROCESS_MARGIN));
	}

	@Test
	void f4RecreatesInventoryServiceWithASmallPoolAndADelay() {
		control.activate(Fault.F4);

		Command command = runner.commands.get(0);
		assertThat(command.argv()).last().isEqualTo("inventory-service");
		assertThat(command.setEnv())
			.isEqualTo(Map.of("INVENTORY_POOL_SIZE", "2", "INVENTORY_RESERVATION_DELAY", "5s"));
	}

	@Test
	void restoreRunsTheSameCommandWithTheFaultVariablesRemoved() {
		control.activate(Fault.F4);
		control.restore(Fault.F4);

		Command activation = runner.commands.get(0);
		Command restore = runner.commands.get(1);
		assertThat(restore.argv()).isEqualTo(activation.argv());
		assertThat(restore.setEnv()).isEmpty();
		assertThat(restore.unsetEnv()).isEqualTo(Set.of("INVENTORY_POOL_SIZE", "INVENTORY_RESERVATION_DELAY"));
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
	void aGatewayFaultIsRefusedWithoutRunningAnything() {
		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F1));
		assertThat(runner.commands).isEmpty();
	}

	@Test
	void theWaitTimeoutMustBeAtLeastOneSecond() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
				() -> new ComposeRecreateControl(runner, COMPOSE_FILE, "kintsugi-lab", Duration.ofMillis(500)));
	}

}
