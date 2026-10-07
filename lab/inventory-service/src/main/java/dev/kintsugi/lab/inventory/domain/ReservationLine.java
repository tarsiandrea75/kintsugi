package dev.kintsugi.lab.inventory.domain;

import java.util.Objects;

/**
 * One line of a reservation: take {@code quantity} units of {@code sku}.
 *
 * @param sku the SKU, not blank and at most {@value #MAX_SKU_LENGTH} characters (the column width)
 * @param quantity units to take, positive
 */
public record ReservationLine(String sku, int quantity) {

	public static final int MAX_SKU_LENGTH = 64;

	public ReservationLine {
		Objects.requireNonNull(sku, "sku");
		if (sku.isBlank() || sku.length() > MAX_SKU_LENGTH) {
			throw new IllegalArgumentException("sku must be 1 to " + MAX_SKU_LENGTH + " characters and not blank");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, was " + quantity);
		}
	}

}
