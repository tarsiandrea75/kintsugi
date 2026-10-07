package dev.kintsugi.lab.payment.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.persistence.PaymentStatusCounts;

/**
 * The count query against a real PostgreSQL whose {@code payments} table is locked by another connection, so the query
 * blocks the way it would on a hung database. No test transaction: a cancelled statement would abort it.
 */
@DataJdbcTest
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentsByStatusMetricsDatabaseTest {

	/** Short, so the test stays fast; production uses {@link PaymentStatusCounts#QUERY_TIMEOUT}. */
	private static final Duration TIMEOUT = Duration.ofSeconds(1);

	@Autowired
	private DataSource dataSource;

	/** Holds an ACCESS EXCLUSIVE lock on {@code payments} until closed, which blocks every read of the table. */
	private Connection lockPayments() throws Exception {
		Connection connection = dataSource.getConnection();
		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.execute("LOCK TABLE payments IN ACCESS EXCLUSIVE MODE");
		}
		return connection;
	}

	@Test
	void theProductionQueryTimeoutIsThreeSeconds() {
		assertThat(PaymentStatusCounts.QUERY_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
	}

	@Test
	void aQueryBlockedOnALockEndsWithATimeout() throws Exception {
		PaymentStatusCounts counts = new PaymentStatusCounts(dataSource, TIMEOUT);

		try (Connection lock = lockPayments()) {
			long start = System.nanoTime();
			assertThatExceptionOfType(DataAccessException.class).isThrownBy(counts::countPaymentsPerStatus);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(TIMEOUT.minusMillis(100),
					TIMEOUT.plusSeconds(2));
			lock.rollback();
		}
	}

	@Test
	void aRefreshBlockedOnALockEndsAndTheGaugeRecoversOnTheNextRefresh() throws Exception {
		PaymentStatusCounts counts = new PaymentStatusCounts(dataSource, TIMEOUT);
		AtomicLong nanoTime = new AtomicLong();
		List<Runnable> pendingRefreshes = new ArrayList<>();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new PaymentsByStatusMetrics(counts::countPaymentsPerStatus, nanoTime::get, pendingRefreshes::add).bindTo(registry);

		try (Connection lock = lockPayments()) {
			gauge(registry);
			assertThat(pendingRefreshes).hasSize(1);
			// Runs on this thread and blocks on the lock until the query timeout cancels it.
			runAll(pendingRefreshes);
			assertThat(gauge(registry)).isNaN();
			lock.rollback();
		}

		nanoTime.addAndGet(PaymentsByStatusMetrics.REFRESH_AFTER.plusNanos(1).toNanos());
		gauge(registry);
		// A new refresh was queued, so the timed-out one released the single-flight guard.
		assertThat(pendingRefreshes).hasSize(1);
		runAll(pendingRefreshes);
		assertThat(gauge(registry)).isNotNaN().isGreaterThanOrEqualTo(0.0);
	}

	private static double gauge(SimpleMeterRegistry registry) {
		return registry.get(PaymentsByStatusMetrics.GAUGE).tag("status", PaymentStatus.AUTHORIZED.name()).gauge().value();
	}

	private static void runAll(List<Runnable> tasks) {
		List<Runnable> copy = new ArrayList<>(tasks);
		tasks.clear();
		copy.forEach(Runnable::run);
	}

}
