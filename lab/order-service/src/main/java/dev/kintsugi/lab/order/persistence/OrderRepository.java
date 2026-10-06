package dev.kintsugi.lab.order.persistence;

import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

import dev.kintsugi.lab.order.domain.Order;

/** Stores and loads the whole {@link Order} aggregate, items included. */
public interface OrderRepository extends ListCrudRepository<Order, UUID> {
}
