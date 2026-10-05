package dev.kintsugi.lab.order.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body of {@code POST /orders}. */
public record CreateOrderRequest(@NotBlank String customerId, @NotEmpty List<@Valid @NotNull Item> items) {

	public record Item(@NotBlank String sku, @NotNull @Positive Integer quantity) {
	}

}
