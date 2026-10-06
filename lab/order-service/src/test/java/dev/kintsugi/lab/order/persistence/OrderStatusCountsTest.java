package dev.kintsugi.lab.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;

import dev.kintsugi.lab.order.TestcontainersConfiguration;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.domain.OrderStatus;

@DataJdbcTest
@Import(TestcontainersConfiguration.class)
class OrderStatusCountsTest {

	@Autowired
	private OrderRepository repository;

	@Autowired
	private DataSource dataSource;

	@Test
	void countsOrdersPerStatusAndOmitsStatusesWithNoOrders() {
		repository.save(Order.create("customer-1", List.of(new OrderItem("SKU-1", 1))));
		repository.save(Order.create("customer-2", List.of(new OrderItem("SKU-1", 1))));
		Order pending = Order.create("customer-3", List.of(new OrderItem("SKU-1", 1)));
		pending.transitionTo(OrderStatus.PAYMENT_PENDING);
		repository.save(pending);

		// Same thread, so the query joins the test transaction and sees the uncommitted rows.
		List<OrderStatusCount> counts = new OrderStatusCounts(dataSource).countOrdersPerStatus();

		assertThat(counts).containsExactlyInAnyOrder(new OrderStatusCount(OrderStatus.CREATED, 2),
				new OrderStatusCount(OrderStatus.PAYMENT_PENDING, 1));
	}

}
