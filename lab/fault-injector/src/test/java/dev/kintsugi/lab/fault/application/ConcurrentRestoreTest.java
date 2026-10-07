package dev.kintsugi.lab.fault.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.control.FakeLabControl.HeldRestore;
import dev.kintsugi.lab.fault.control.FakeLabControlConfiguration;
import dev.kintsugi.lab.fault.domain.Injection;
import dev.kintsugi.lab.fault.domain.InjectionStatus;

/**
 * Two restores of the same injection overlap: one fails while the other succeeds. A failure that finishes after the row
 * is already {@code RESTORED} must not move it back to {@code RESTORE_PENDING}; it is recorded as an event only.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakeLabControlConfiguration.class })
class ConcurrentRestoreTest {

	@Autowired
	private FaultInjectionService service;

	@Autowired
	private FakeLabControl lab;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void emptyLabAndTables() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
		lab.reset();
	}

	@Test
	void aRestoreFailureThatEndsAfterASuccessfulRestoreLeavesTheRowRestored() throws Exception {
		UUID id = service.activate("F1").id();
		HeldRestore failing = lab.holdNextRestore(true);

		try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
			// The failing restore reads the row while it is still ACTIVE, then waits inside the lab step.
			Future<Injection> late = pool.submit(() -> service.restore(id));
			failing.awaitEntered();

			// Meanwhile a second restore succeeds and commits RESTORED.
			assertThat(service.restore(id).status()).isEqualTo(InjectionStatus.RESTORED);

			failing.release();
			assertThatExceptionOfType(ExecutionException.class).isThrownBy(() -> late.get(10, TimeUnit.SECONDS))
				.withCauseInstanceOf(RestoreFailedException.class);
		}

		assertThat(jdbc.sql("SELECT status FROM injections WHERE id = ?").param(id).query(String.class).single())
			.isEqualTo("RESTORED");
		assertThat(jdbc.sql("SELECT restored_at IS NOT NULL FROM injections WHERE id = ?")
			.param(id)
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATED", "RESTORED", "RESTORE_FAILED");
		assertThat(service.blocking()).isEmpty();
	}

	private List<String> events(UUID id) {
		return jdbc.sql("SELECT type FROM injection_events WHERE injection_id = ? ORDER BY id")
			.param(id)
			.query(String.class)
			.list();
	}

}
