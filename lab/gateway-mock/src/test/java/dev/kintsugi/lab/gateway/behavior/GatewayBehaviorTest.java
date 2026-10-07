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

	@Test
	void defaultBehaviorHasNoErrorStatus() {
		assertThat(GatewayBehavior.DEFAULT.errorStatus()).isNull();
	}

	@Test
	void latencyOnlyConstructorHasNoErrorStatus() {
		assertThat(new GatewayBehavior(500).errorStatus()).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = { 429, 500, 503 })
	void allowedErrorStatusIsKept(int errorStatus) {
		assertThat(new GatewayBehavior(0, errorStatus).errorStatus()).isEqualTo(errorStatus);
	}

	@Test
	void nullErrorStatusMeansNoError() {
		assertThat(new GatewayBehavior(0, null).errorStatus()).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 200, 400, 404, 428, 502, 504, 999, -1 })
	void otherErrorStatusIsRejected(int errorStatus) {
		assertThatIllegalArgumentException().isThrownBy(() -> new GatewayBehavior(0, errorStatus));
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
