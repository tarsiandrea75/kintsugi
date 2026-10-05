package dev.kintsugi.lab.order.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderStatus;

/** Order as returned by the API; keeps persistence details such as the version out of the contract. */
public record OrderResponse(UUID id, String customerId, List<Item> items, OrderStatus status, Instant createdAt,
		Instant updatedAt) {

	public record Item(String sku, int quantity) {
	}

	static OrderResponse from(Order order) {
		List<Item> items = order.getItems().stream().map(item -> new Item(item.sku(), item.quantity())).toList();
		return new OrderResponse(order.getId(), order.getCustomerId(), items, order.getStatus(),
				order.getCreatedAt(), order.getUpdatedAt());
	}

}
