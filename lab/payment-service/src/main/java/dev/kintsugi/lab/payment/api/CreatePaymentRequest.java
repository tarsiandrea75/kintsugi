package dev.kintsugi.lab.payment.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /payments}. A missing or null {@code orderId} fails {@code @NotNull}; a value that is not
 * a UUID fails JSON binding. Both are a 400.
 */
public record CreatePaymentRequest(@NotNull UUID orderId) {

}
