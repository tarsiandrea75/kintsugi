package dev.kintsugi.lab.gateway.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import dev.kintsugi.lab.gateway.behavior.GatewayBehavior;

/**
 * Body of {@code PUT /admin/behavior}. It replaces the whole behavior, so a missing {@code errorStatus} means no
 * error. The checks repeat {@link GatewayBehavior}'s rules so a bad value is a 400 from validation, before the domain
 * record is built.
 */
record BehaviorRequest(@NotNull @Min(GatewayBehavior.MIN_LATENCY_MS) @Max(GatewayBehavior.MAX_LATENCY_MS) Long latencyMs,
		Integer errorStatus) {

	@AssertTrue(message = "errorStatus must be 429, 500, 503 or null")
	boolean isErrorStatusAllowed() {
		return errorStatus == null || GatewayBehavior.ALLOWED_ERROR_STATUSES.contains(errorStatus);
	}

	GatewayBehavior toBehavior() {
		return new GatewayBehavior(latencyMs, errorStatus);
	}

}
