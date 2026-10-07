package dev.kintsugi.lab.payment.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.persistence.PaymentStatusCounts;
import dev.kintsugi.lab.payment.persistence.PaymentStatusCount;

/**
 * Deterministic by default: the clock is a counter and refreshes are queued until {@link #runRefreshes()} runs them.
 * Only {@link #gaugeReadsNeverWaitForASlowQuery()} uses a real virtual thread.
 */
class PaymentsByStatusMetricsTest {

	private static final long PAST_REFRESH_AFTER = PaymentsByStatusMetrics.REFRESH_AFTER.plus(Duration.ofNanos(1)).toNanos();

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final AtomicLong nanoTime = new AtomicLong();

	private final AtomicInteger queries = new AtomicInteger();

	private final List<Runnable> pendingRefreshes = new ArrayList<>();

	private Executor executor = pendingRefreshes::add;

	private List<PaymentStatusCount> rows = List.of();

	/** The query behind the gauges; by default it returns {@link #rows}. */
	private Supplier<List<PaymentStatusCount>> query = () -> rows;

	private double gauge(PaymentStatus status) {
		return registry.get(PaymentsByStatusMetrics.GAUGE).tag("status", status.name()).gauge().value();
	}

	/** One scrape: every series read once. */
	private void scrape() {
		for (PaymentStatus status : PaymentStatus.values()) {
			gauge(status);
		}
	}

	private void runRefreshes() {
		List<Runnable> tasks = new ArrayList<>(pendingRefreshes);
		pendingRefreshes.clear();
		tasks.forEach(Runnable::run);
	}

	private PaymentsByStatusMetrics bind() {
		PaymentsByStatusMetrics metrics = new PaymentsByStatusMetrics(() -> {
			queries.incrementAndGet();
			return query.get();
		}, nanoTime::get, executor);
		metrics.bindTo(registry);
		return metrics;
	}

	private void failQueries() {
		query = () -> {
			throw new DataAccessResourceFailureException("database down");
		};
	}

	@Test
	void reportsNaNUntilTheFirstRefreshCompletes() {
		bind();

		scrape();

		assertThat(gauge(PaymentStatus.AUTHORIZED)).isNaN();
		assertThat(queries).hasValue(0);
	}

	@Test
	void reportsTheCountOfEveryStatusAndZeroForStatusesWithNoPayments() {
		rows = List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 3), new PaymentStatusCount(PaymentStatus.DECLINED, 2));
		bind();
		scrape();
		runRefreshes();

		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(3.0);
		assertThat(gauge(PaymentStatus.DECLINED)).isEqualTo(2.0);
		assertThat(gauge(PaymentStatus.FAILED)).isZero();
	}

	@Test
	void registersOneSeriesPerStatus() {
		bind();

		assertThat(registry.get(PaymentsByStatusMetrics.GAUGE).gauges()).hasSize(PaymentStatus.values().length);
	}

	@Test
	void oneScrapeStartsASingleRefreshAndItsResultServesTheTtl() {
		bind();

		scrape();
		assertThat(pendingRefreshes).hasSize(1);
		runRefreshes();
		scrape();

		assertThat(pendingRefreshes).isEmpty();
		assertThat(queries).hasValue(1);
	}

	@Test
	void aStaleSnapshotIsServedWhileTheNextRefreshRuns() {
		rows = List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 1));
		bind();
		scrape();
		runRefreshes();

		rows = List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 4));
		nanoTime.addAndGet(PAST_REFRESH_AFTER);

		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(1.0);
		runRefreshes();
		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(4.0);
		assertThat(queries).hasValue(2);
	}

	@Test
	void gaugeReadsNeverWaitForASlowQuery() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		query = () -> {
			try {
				release.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 5));
		};
		executor = task -> Thread.ofVirtual().start(task);
		bind();

		long start = System.nanoTime();
		scrape();
		long elapsed = System.nanoTime() - start;
		// Read before releasing the query, otherwise the background refresh can finish first.
		double whileQueryIsRunning = gauge(PaymentStatus.AUTHORIZED);
		release.countDown();

		assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofMillis(100));
		assertThat(whileQueryIsRunning).isNaN();
	}

	@Test
	void aFailingQueryGivesNaNAndRunsAtMostOncePerTtl() {
		PaymentStatusCounts counts = mock(PaymentStatusCounts.class);
		when(counts.countPaymentsPerStatus()).thenThrow(new DataAccessResourceFailureException("pool exhausted"));
		query = counts::countPaymentsPerStatus;
		PaymentsByStatusMetrics metrics = bind();

		scrape();
		runRefreshes();
		scrape();
		scrape();
		runRefreshes();

		for (PaymentStatus status : PaymentStatus.values()) {
			assertThat(gauge(status)).isNaN();
		}
		assertThat(metrics.count(PaymentStatus.AUTHORIZED)).isNaN();
		assertThat(queries).hasValue(1);

		nanoTime.addAndGet(PAST_REFRESH_AFTER);
		scrape();
		runRefreshes();
		assertThat(queries).hasValue(2);
	}

	@Test
	void lastGoodCountsAreReplacedByNaNOnceARefreshFails() {
		rows = List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 1));
		bind();
		scrape();
		runRefreshes();

		failQueries();
		nanoTime.addAndGet(PAST_REFRESH_AFTER);
		scrape();
		runRefreshes();

		assertThat(gauge(PaymentStatus.AUTHORIZED)).isNaN();
	}

	@Test
	void aHungRefreshTurnsTheGaugeNaNOnceTheSnapshotIsOlderThanServeAtMost() {
		rows = List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 1));
		bind();
		nanoTime.set(1_000);
		scrape();
		runRefreshes();
		long snapshotTakenAt = nanoTime.get();

		// The next refresh starts but never completes, like a query stuck on a hung connection.
		nanoTime.addAndGet(PAST_REFRESH_AFTER);
		scrape();
		assertThat(pendingRefreshes).hasSize(1);
		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(1.0);

		nanoTime.set(snapshotTakenAt + PaymentsByStatusMetrics.SERVE_AT_MOST.toNanos());
		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(1.0);

		nanoTime.incrementAndGet();
		for (PaymentStatus status : PaymentStatus.values()) {
			assertThat(gauge(status)).isNaN();
		}
		assertThat(pendingRefreshes).hasSize(1);
		assertThat(queries).hasValue(1);

		runRefreshes();
		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(1.0);
	}

	@Test
	void anErrorStartingTheRefreshIsRethrownAndReleasesTheGuard() {
		AtomicInteger starts = new AtomicInteger();
		executor = task -> {
			if (starts.incrementAndGet() == 1) {
				throw new OutOfMemoryError("unable to create native thread");
			}
			pendingRefreshes.add(task);
		};
		PaymentsByStatusMetrics metrics = bind();

		assertThatThrownBy(() -> metrics.count(PaymentStatus.AUTHORIZED)).isInstanceOf(OutOfMemoryError.class);
		metrics.count(PaymentStatus.AUTHORIZED);

		assertThat(pendingRefreshes).hasSize(1);
	}

	@Test
	void countsComeBackAfterTheDatabaseRecovers() {
		failQueries();
		bind();
		scrape();
		runRefreshes();
		assertThat(gauge(PaymentStatus.AUTHORIZED)).isNaN();

		rows = List.of(new PaymentStatusCount(PaymentStatus.AUTHORIZED, 2));
		query = () -> rows;
		nanoTime.addAndGet(PAST_REFRESH_AFTER);
		scrape();
		runRefreshes();

		assertThat(gauge(PaymentStatus.AUTHORIZED)).isEqualTo(2.0);
		assertThat(gauge(PaymentStatus.FAILED)).isZero();
	}

}
