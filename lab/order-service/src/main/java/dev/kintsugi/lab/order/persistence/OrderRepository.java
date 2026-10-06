package dev.kintsugi.lab.order.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

import dev.kintsugi.lab.order.domain.Order;

/** Stores and loads the whole {@link Order} aggregate, items included. */
public interface OrderRepository extends ListCrudRepository<Order, UUID> {

	/** One row per status that has at least one order; statuses with no orders are absent. */
	@Query("SELECT status, count(*) AS count FROM orders GROUP BY status")
	List<OrderStatusCount> countOrdersPerStatus();

}
