package dev.kintsugi.lab.gateway.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import dev.kintsugi.lab.gateway.behavior.GatewayBehavior;

/**
 * Body of {@code PUT /admin/behavior}. The bounds repeat {@link GatewayBehavior}'s rule so a bad value is a 400 from
 * validation, before the domain record is built.
 */
record BehaviorRequest(
		@NotNull @Min(GatewayBehavior.MIN_LATENCY_MS) @Max(GatewayBehavior.MAX_LATENCY_MS) Long latencyMs) {

	GatewayBehavior toBehavior() {
		return new GatewayBehavior(latencyMs);
	}

}
