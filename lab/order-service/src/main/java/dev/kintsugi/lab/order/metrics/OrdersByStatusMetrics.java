package dev.kintsugi.lab.order.metrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.persistence.OrderRepository;
import dev.kintsugi.lab.order.persistence.OrderStatusCount;

/**
 * Gauge {@value #GAUGE} with one series per {@link OrderStatus} (tag {@code status}), read from the database at scrape
 * time. Orders piling up in {@code PAYMENT_PENDING} is how fault F1 shows in the metrics.
 *
 * <p>The gauges of one scrape share a single {@code GROUP BY} query: the result is reused while it is younger than
 * {@link #SNAPSHOT_MAX_AGE}, so a scrape costs one query and one pooled connection, not one per status.
 */
@Component
public class OrdersByStatusMetrics implements MeterBinder {

	/** Exported to Prometheus as {@code lab_orders{status="..."}}. */
	static final String GAUGE = "lab.orders";

	/** Far shorter than any scrape interval, far longer than reading the gauges of one scrape. */
	static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(1);

	private final Supplier<List<OrderStatusCount>> query;

	private final LongSupplier nanoTime;

	private Map<OrderStatus, Long> snapshot;

	private long snapshotTakenAt;

	@Autowired
	public OrdersByStatusMetrics(OrderRepository repository) {
		this(repository::countOrdersPerStatus, System::nanoTime);
	}

	OrdersByStatusMetrics(Supplier<List<OrderStatusCount>> query, LongSupplier nanoTime) {
		this.query = query;
		this.nanoTime = nanoTime;
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

	/** Statuses with no orders report 0, so every series always has a value. */
	synchronized long count(OrderStatus status) {
		long now = nanoTime.getAsLong();
		if (snapshot == null || now - snapshotTakenAt > SNAPSHOT_MAX_AGE.toNanos()) {
			Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
			for (OrderStatusCount row : query.get()) {
				counts.put(row.status(), row.count());
			}
			snapshot = counts;
			snapshotTakenAt = now;
		}
		return snapshot.getOrDefault(status, 0L);
	}

}
