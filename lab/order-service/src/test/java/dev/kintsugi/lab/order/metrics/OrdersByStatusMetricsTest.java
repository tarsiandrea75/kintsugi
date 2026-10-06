package dev.kintsugi.lab.order.metrics;

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

import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.persistence.OrderRepository;
import dev.kintsugi.lab.order.persistence.OrderStatusCount;

/**
 * Deterministic by default: the clock is a counter and refreshes are queued until {@link #runRefreshes()} runs them.
 * Only {@link #gaugeReadsNeverWaitForASlowQuery()} uses a real virtual thread.
 */
class OrdersByStatusMetricsTest {

	private static final long PAST_TTL = OrdersByStatusMetrics.SNAPSHOT_MAX_AGE.plus(Duration.ofNanos(1)).toNanos();

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final AtomicLong nanoTime = new AtomicLong();

	private final AtomicInteger queries = new AtomicInteger();

	private final List<Runnable> pendingRefreshes = new ArrayList<>();

	private Executor executor = pendingRefreshes::add;

	private List<OrderStatusCount> rows = List.of();

	/** The query behind the gauges; by default it returns {@link #rows}. */
	private Supplier<List<OrderStatusCount>> query = () -> rows;

	private double gauge(OrderStatus status) {
		return registry.get(OrdersByStatusMetrics.GAUGE).tag("status", status.name()).gauge().value();
	}

	/** One scrape: every series read once. */
	private void scrape() {
		for (OrderStatus status : OrderStatus.values()) {
			gauge(status);
		}
	}

	private void runRefreshes() {
		List<Runnable> tasks = new ArrayList<>(pendingRefreshes);
		pendingRefreshes.clear();
		tasks.forEach(Runnable::run);
	}

	private OrdersByStatusMetrics bind() {
		OrdersByStatusMetrics metrics = new OrdersByStatusMetrics(() -> {
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

		assertThat(gauge(OrderStatus.PAID)).isNaN();
		assertThat(queries).hasValue(0);
	}

	@Test
	void reportsTheCountOfEveryStatusAndZeroForStatusesWithNoOrders() {
		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 3), new OrderStatusCount(OrderStatus.PAYMENT_PENDING, 2));
		bind();
		scrape();
		runRefreshes();

		assertThat(gauge(OrderStatus.PAID)).isEqualTo(3.0);
		assertThat(gauge(OrderStatus.PAYMENT_PENDING)).isEqualTo(2.0);
		assertThat(gauge(OrderStatus.CREATED)).isZero();
		assertThat(gauge(OrderStatus.PAYMENT_FAILED)).isZero();
	}

	@Test
	void registersOneSeriesPerStatus() {
		bind();

		assertThat(registry.get(OrdersByStatusMetrics.GAUGE).gauges()).hasSize(OrderStatus.values().length);
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
		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 1));
		bind();
		scrape();
		runRefreshes();

		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 4));
		nanoTime.addAndGet(PAST_TTL);

		assertThat(gauge(OrderStatus.PAID)).isEqualTo(1.0);
		runRefreshes();
		assertThat(gauge(OrderStatus.PAID)).isEqualTo(4.0);
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
			return List.of(new OrderStatusCount(OrderStatus.PAID, 5));
		};
		executor = task -> Thread.ofVirtual().start(task);
		bind();

		long start = System.nanoTime();
		scrape();
		long elapsed = System.nanoTime() - start;
		// Read before releasing the query, otherwise the background refresh can finish first.
		double whileQueryIsRunning = gauge(OrderStatus.PAID);
		release.countDown();

		assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofMillis(100));
		assertThat(whileQueryIsRunning).isNaN();
	}

	@Test
	void aFailingQueryGivesNaNAndRunsAtMostOncePerTtl() {
		OrderRepository repository = mock(OrderRepository.class);
		when(repository.countOrdersPerStatus()).thenThrow(new DataAccessResourceFailureException("pool exhausted"));
		query = repository::countOrdersPerStatus;
		OrdersByStatusMetrics metrics = bind();

		scrape();
		runRefreshes();
		scrape();
		scrape();
		runRefreshes();

		for (OrderStatus status : OrderStatus.values()) {
			assertThat(gauge(status)).isNaN();
		}
		assertThat(metrics.count(OrderStatus.PAID)).isNaN();
		assertThat(queries).hasValue(1);

		nanoTime.addAndGet(PAST_TTL);
		scrape();
		runRefreshes();
		assertThat(queries).hasValue(2);
	}

	@Test
	void lastGoodCountsAreReplacedByNaNOnceARefreshFails() {
		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 1));
		bind();
		scrape();
		runRefreshes();

		failQueries();
		nanoTime.addAndGet(PAST_TTL);
		scrape();
		runRefreshes();

		assertThat(gauge(OrderStatus.PAID)).isNaN();
	}

	@Test
	void aHungRefreshTurnsTheGaugeNaNAfterTheMaxServedAge() {
		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 1));
		bind();
		scrape();
		runRefreshes();

		// The next refresh starts but never completes, like a query stuck on a hung connection.
		nanoTime.addAndGet(PAST_TTL);
		scrape();
		assertThat(pendingRefreshes).hasSize(1);
		assertThat(gauge(OrderStatus.PAID)).isEqualTo(1.0);

		nanoTime.set(OrdersByStatusMetrics.MAX_SERVED_AGE.toNanos());
		assertThat(gauge(OrderStatus.PAID)).isEqualTo(1.0);

		nanoTime.incrementAndGet();
		for (OrderStatus status : OrderStatus.values()) {
			assertThat(gauge(status)).isNaN();
		}
		assertThat(pendingRefreshes).hasSize(1);
		assertThat(queries).hasValue(1);

		runRefreshes();
		assertThat(gauge(OrderStatus.PAID)).isEqualTo(1.0);
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
		OrdersByStatusMetrics metrics = bind();

		assertThatThrownBy(() -> metrics.count(OrderStatus.PAID)).isInstanceOf(OutOfMemoryError.class);
		metrics.count(OrderStatus.PAID);

		assertThat(pendingRefreshes).hasSize(1);
	}

	@Test
	void countsComeBackAfterTheDatabaseRecovers() {
		failQueries();
		bind();
		scrape();
		runRefreshes();
		assertThat(gauge(OrderStatus.PAID)).isNaN();

		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 2));
		query = () -> rows;
		nanoTime.addAndGet(PAST_TTL);
		scrape();
		runRefreshes();

		assertThat(gauge(OrderStatus.PAID)).isEqualTo(2.0);
		assertThat(gauge(OrderStatus.CREATED)).isZero();
	}

}
