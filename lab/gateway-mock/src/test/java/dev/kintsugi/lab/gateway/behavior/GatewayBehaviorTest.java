package dev.kintsugi.lab.gateway.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GatewayBehaviorTest {

	@Test
	void defaultBehaviorHasNoLatency() {
		assertThat(GatewayBehavior.DEFAULT.latencyMs()).isZero();
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, 1, 10_000, GatewayBehavior.MAX_LATENCY_MS })
	void latencyInRangeIsKept(long latencyMs) {
		assertThat(new GatewayBehavior(latencyMs).latencyMs()).isEqualTo(latencyMs);
	}

	@ParameterizedTest
	@ValueSource(longs = { -1, GatewayBehavior.MAX_LATENCY_MS + 1, Long.MIN_VALUE, Long.MAX_VALUE })
	void latencyOutOfRangeIsRejected(long latencyMs) {
		assertThatIllegalArgumentException().isThrownBy(() -> new GatewayBehavior(latencyMs));
	}

}
