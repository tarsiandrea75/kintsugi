package dev.kintsugi.lab.gateway.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /authorize}. A missing or null {@code orderId} fails {@code @NotNull}; a value that is not a
 * UUID fails JSON binding. Both are a 400.
 */
record AuthorizeRequest(@NotNull UUID orderId) {

}
