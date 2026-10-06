package dev.kintsugi.lab.order.metrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.persistence.OrderRepository;
import dev.kintsugi.lab.order.persistence.OrderStatusCount;

/**
 * Gauge {@value #GAUGE} with one series per {@link OrderStatus} (tag {@code status}), read from the database. Orders
 * piling up in {@code PAYMENT_PENDING} is how fault F1 shows in the metrics.
 *
 * <p>A scrape never waits on the database. A gauge read returns the current snapshot at once: the last good counts,
 * or NaN after a failed refresh or before the first refresh completes. When the snapshot is older than
 * {@link #SNAPSHOT_MAX_AGE}, the read starts one asynchronous refresh (single-flight) and does not wait for it. The
 * refresh runs one {@code GROUP BY} query and stores the counts, or a failure, timestamped when the query finishes, so
 * a failing database is queried at most once per max age and never once per series. If the database hangs (fault
 * F4, pool exhausted), scrapes stay fast and the series show NaN or the last counts instead of timing out the target.
 *
 * <p>Trade-off: a value can be up to about one max age plus one query old. That is fine for a scrape interval of
 * several seconds.
 */
@Component
public class OrdersByStatusMetrics implements MeterBinder {

	/** Exported to Prometheus as {@code lab_orders{status="..."}}. */
	static final String GAUGE = "lab.orders";

	/** Far shorter than any scrape interval, far longer than reading the gauges of one scrape. */
	static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(1);

	private static final Logger log = LoggerFactory.getLogger(OrdersByStatusMetrics.class);

	private final Supplier<List<OrderStatusCount>> query;

	private final LongSupplier nanoTime;

	private final Executor refresher;

	/** Null until the first refresh completes. */
	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

	/** Single-flight guard: true while a refresh is queued or running. */
	private final AtomicBoolean refreshing = new AtomicBoolean();

	/** True from a failed query until the next successful one, so a failure burst logs one WARN. */
	private final AtomicBoolean failing = new AtomicBoolean();

	@Autowired
	public OrdersByStatusMetrics(OrderRepository repository) {
		this(repository::countOrdersPerStatus, System::nanoTime,
				task -> Thread.ofVirtual().name("lab-orders-refresh").start(task));
	}

	OrdersByStatusMetrics(Supplier<List<OrderStatusCount>> query, LongSupplier nanoTime, Executor refresher) {
		this.query = query;
		this.nanoTime = nanoTime;
		this.refresher = refresher;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		for (OrderStatus status : OrderStatus.values()) {
			Gauge.builder(GAUGE, this, metrics -> metrics.count(status))
				.description("Stored orders per status")
				.tag("status", status.name())
				// Gauges hold a weak reference by default; keep this binder alive as long as the registry.
				.strongReference(true)
				.register(registry);
		}
	}

	/** Statuses with no orders report 0, so every series has a value; NaN when the counts are unknown. */
	double count(OrderStatus status) {
		Snapshot current = snapshot.get();
		if ((current == null || current.isOlderThan(SNAPSHOT_MAX_AGE.toNanos(), nanoTime.getAsLong()))
				&& refreshing.compareAndSet(false, true)) {
			startRefresh();
		}
		if (current == null || current.failed()) {
			return Double.NaN;
		}
		return current.counts().getOrDefault(status, 0L);
	}

	private void startRefresh() {
		try {
			refresher.execute(this::refresh);
		}
		catch (RuntimeException ex) {
			// Could not even start the refresh; release the guard so the next read tries again.
			refreshing.set(false);
			log.debug("Cannot start the order count refresh", ex);
		}
	}

	private void refresh() {
		try {
			Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
			for (OrderStatusCount row : query.get()) {
				counts.put(row.status(), row.count());
			}
			snapshot.set(new Snapshot(counts, nanoTime.getAsLong()));
			if (failing.compareAndSet(true, false)) {
				log.info("Order counts are readable again");
			}
		}
		catch (RuntimeException ex) {
			// The failure is stored like a result, so it is not re-queried before the max age passes.
			snapshot.set(new Snapshot(null, nanoTime.getAsLong()));
			if (failing.compareAndSet(false, true)) {
				log.warn("Cannot count orders per status; lab.orders reports NaN until the query succeeds", ex);
			}
			else {
				log.debug("Counting orders per status failed again", ex);
			}
		}
		finally {
			refreshing.set(false);
		}
	}

	/** Counts per status, or {@code null} counts for a failed query; {@code takenAt} is when the query finished. */
	private record Snapshot(Map<OrderStatus, Long> counts, long takenAt) {

		boolean failed() {
			return counts == null;
		}

		boolean isOlderThan(long maxAgeNanos, long now) {
			return now - takenAt > maxAgeNanos;
		}

	}

}
