package dev.kintsugi.lab.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import dev.kintsugi.lab.order.domain.Order;

/**
 * Body of {@code POST /orders}. The constraints mirror the domain rules in {@link Order}, so invalid input is a
 * 400 here instead of an exception in the domain. {@code @NotBlank} uses {@code String.isBlank()}, the same
 * predicate as the domain.
 */
public record CreateOrderRequest(@NotBlank @Size(max = Order.MAX_ID_LENGTH) String customerId,
		@NotEmpty @Size(max = Order.MAX_ITEMS) List<@Valid @NotNull Item> items) {

	public record Item(@NotBlank @Size(max = Order.MAX_ID_LENGTH) String sku, @NotNull @Positive Integer quantity) {
	}

}
