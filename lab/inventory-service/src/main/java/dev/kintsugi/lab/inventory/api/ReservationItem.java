package dev.kintsugi.lab.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import dev.kintsugi.lab.inventory.domain.ReservationLine;

/** One line of {@code POST /reservations}. */
public record ReservationItem(@NotBlank @Size(max = ReservationLine.MAX_SKU_LENGTH) String sku,
		@NotNull @Positive Integer quantity) {

	ReservationLine toLine() {
		return new ReservationLine(sku, quantity);
	}

}
