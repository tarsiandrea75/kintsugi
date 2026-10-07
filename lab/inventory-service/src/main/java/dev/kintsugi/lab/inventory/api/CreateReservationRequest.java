package dev.kintsugi.lab.inventory.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import dev.kintsugi.lab.inventory.domain.Reservation;

/**
 * Body of {@code POST /reservations}. A broken rule fails bean validation and a value of the wrong type fails JSON
 * binding; both are a 400.
 */
public record CreateReservationRequest(@NotNull UUID orderId,
		@NotEmpty @Size(max = Reservation.MAX_LINES) List<@NotNull @Valid ReservationItem> items) {

	Reservation toReservation() {
		return new Reservation(orderId, items.stream().map(ReservationItem::toLine).toList());
	}

}
