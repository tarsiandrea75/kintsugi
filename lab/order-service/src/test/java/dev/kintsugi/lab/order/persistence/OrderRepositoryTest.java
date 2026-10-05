package dev.kintsugi.lab.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.order.TestcontainersConfiguration;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.domain.OrderStatus;

@DataJdbcTest
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {

	@Autowired
	private OrderRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void runsAgainstPostgres18WithTheFlywaySchema() {
		String version = jdbc.sql("select version()").query(String.class).single();
		Integer appliedMigrations = jdbc
			.sql("select count(*) from flyway_schema_history where success and version is not null")
			.query(Integer.class)
			.single();
		List<String> tables = jdbc
			.sql("select table_name from information_schema.tables where table_schema = 'public'")
			.query(String.class)
			.list();

		assertThat(version).startsWith("PostgreSQL 18.6");
		assertThat(appliedMigrations).isPositive();
		assertThat(tables).contains("orders", "order_items");
	}

	@Test
	void savedOrderIsReadBackWithItemsInOrder() {
		Order order = Order.create("customer-1", List.of(new OrderItem("SKU-1", 2), new OrderItem("SKU-2", 5)));

		repository.save(order);
		Order found = repository.findById(order.getId()).orElseThrow();

		assertThat(found.getId()).isEqualTo(order.getId());
		assertThat(found.getCustomerId()).isEqualTo("customer-1");
		assertThat(found.getItems()).containsExactly(new OrderItem("SKU-1", 2), new OrderItem("SKU-2", 5));
		assertThat(found.getStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(found.getCreatedAt()).isEqualTo(order.getCreatedAt());
		assertThat(found.getUpdatedAt()).isEqualTo(order.getUpdatedAt());
	}

	@Test
	void transitionIsPersistedOnUpdate() {
		Order order = repository.save(Order.create("customer-1", List.of(new OrderItem("SKU-1", 1))));

		order.transitionTo(OrderStatus.PAYMENT_PENDING);
		repository.save(order);

		assertThat(repository.findById(order.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PAYMENT_PENDING);
		assertThat(jdbc.sql("select count(*) from order_items where order_id = ?")
			.param(order.getId())
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void savingAStaleCopyFailsWithOptimisticLocking() {
		Order order = repository.save(Order.create("customer-1", List.of(new OrderItem("SKU-1", 1))));
		Order first = repository.findById(order.getId()).orElseThrow();
		Order stale = repository.findById(order.getId()).orElseThrow();

		first.transitionTo(OrderStatus.PAYMENT_PENDING);
		repository.save(first);
		stale.transitionTo(OrderStatus.PAYMENT_PENDING);

		assertThatExceptionOfType(OptimisticLockingFailureException.class).isThrownBy(() -> repository.save(stale));
		assertThat(repository.findById(order.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PAYMENT_PENDING);
	}

	@Test
	void updatedAtIsPersistedAfterATransition() {
		Order order = repository.save(Order.create("customer-1", List.of(new OrderItem("SKU-1", 1))));

		order.transitionTo(OrderStatus.PAYMENT_PENDING);
		repository.save(order);
		Order found = repository.findById(order.getId()).orElseThrow();

		assertThat(found.getUpdatedAt()).isEqualTo(order.getUpdatedAt()).isAfter(found.getCreatedAt());
	}

}
