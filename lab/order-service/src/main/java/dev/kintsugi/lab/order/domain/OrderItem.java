package dev.kintsugi.lab.order.domain;

import org.springframework.data.relational.core.mapping.Table;

/** One line of an order. Part of the {@link Order} aggregate, stored in {@code order_items}. */
@Table("order_items")
public record OrderItem(String sku, int quantity) {

	public OrderItem {
		if (sku == null || sku.isBlank()) {
			throw new IllegalArgumentException("SKU must not be blank");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("Quantity must be greater than 0, was " + quantity);
		}
	}

}
