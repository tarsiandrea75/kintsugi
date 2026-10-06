package dev.kintsugi.lab.order.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.persistence.OrderStatusCount;

class OrdersByStatusMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final AtomicLong nanoTime = new AtomicLong();

	private final AtomicInteger queries = new AtomicInteger();

	private List<OrderStatusCount> rows = List.of();

	private double gauge(OrderStatus status) {
		return registry.get(OrdersByStatusMetrics.GAUGE).tag("status", status.name()).gauge().value();
	}

	private OrdersByStatusMetrics bind() {
		OrdersByStatusMetrics metrics = new OrdersByStatusMetrics(() -> {
			queries.incrementAndGet();
			return rows;
		}, nanoTime::get);
		metrics.bindTo(registry);
		return metrics;
	}

	@Test
	void reportsTheCountOfEveryStatusAndZeroForStatusesWithNoOrders() {
		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 3), new OrderStatusCount(OrderStatus.PAYMENT_PENDING, 2));
		bind();

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
	void oneQueryServesEveryGaugeReadInTheSameScrape() {
		bind();

		for (OrderStatus status : OrderStatus.values()) {
			gauge(status);
		}

		assertThat(queries).hasValue(1);
	}

	@Test
	void queriesAgainOnceTheSnapshotIsOlderThanItsMaxAge() {
		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 1));
		bind();
		assertThat(gauge(OrderStatus.PAID)).isEqualTo(1.0);

		rows = List.of(new OrderStatusCount(OrderStatus.PAID, 4));
		nanoTime.addAndGet(OrdersByStatusMetrics.SNAPSHOT_MAX_AGE.plus(Duration.ofNanos(1)).toNanos());

		assertThat(gauge(OrderStatus.PAID)).isEqualTo(4.0);
		assertThat(queries).hasValue(2);
	}

}
