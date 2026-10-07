package dev.kintsugi.lab.inventory.api;

import java.util.UUID;

import dev.kintsugi.lab.inventory.domain.ReservationStatus;

/** Outcome of {@code POST /reservations}. */
public record ReservationResponse(UUID orderId, ReservationStatus status) {

}
