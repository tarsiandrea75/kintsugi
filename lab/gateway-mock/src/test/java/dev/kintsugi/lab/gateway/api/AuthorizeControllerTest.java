package dev.kintsugi.lab.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import dev.kintsugi.lab.gateway.behavior.BehaviorStore;
import dev.kintsugi.lab.gateway.behavior.GatewayBehavior;

@WebMvcTest(AuthorizeController.class)
@Import({ BehaviorStore.class, AuthorizeControllerTest.Metrics.class })
class AuthorizeControllerTest {

	/** {@code @WebMvcTest} does not auto-configure metrics, so the controller gets an in-memory registry. */
	@TestConfiguration
	static class Metrics {

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MeterRegistry registry;

	@Test
	void inflightGaugeIsBackToZeroAfterAuthorizeCompletes() throws Exception {
		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "11111111-1111-1111-1111-111111111111"}
				"""))
			.andExpect(status().isOk());

		assertThat(registry.get(AuthorizeController.INFLIGHT_GAUGE).gauge().value()).isZero();
	}

	@Test
	void inflightGaugeIsBackToZeroAfterInterruptedAuthorize() {
		BehaviorStore slowStore = new BehaviorStore();
		slowStore.set(new GatewayBehavior(GatewayBehavior.MAX_LATENCY_MS));
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		AuthorizeController controller = new AuthorizeController(slowStore, meters);

		Thread.currentThread().interrupt();
		try {
			assertThatExceptionOfType(ResponseStatusException.class)
				.isThrownBy(() -> controller.authorize(new AuthorizeRequest(UUID.randomUUID())));
		}
		finally {
			Thread.interrupted();
		}
		assertThat(meters.get(AuthorizeController.INFLIGHT_GAUGE).gauge().value()).isZero();
	}

	@Test
	void validOrderIsAuthorized() throws Exception {
		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "11111111-1111-1111-1111-111111111111"}
				"""))
			.andExpect(status().isOk())
			.andExpect(content().json("""
					{"status": "AUTHORIZED"}
					""", true));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"orderId\": null}", "{\"orderId\": \"not-a-uuid\"}", "{\"orderId\": \"" })
	void invalidBodyIsRejected(String body) throws Exception {
		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
	}

	// Characterization: the controller already handled interruption; this pins the 503 the spec now requires.
	@Test
	void interruptedLatencyIsServiceUnavailable() {
		BehaviorStore slowStore = new BehaviorStore();
		slowStore.set(new GatewayBehavior(GatewayBehavior.MAX_LATENCY_MS));
		AuthorizeController controller = new AuthorizeController(slowStore, new SimpleMeterRegistry());

		// An interrupt flag set before the call makes Thread.sleep throw at once, as a shutdown interrupt would.
		Thread.currentThread().interrupt();
		try {
			assertThatExceptionOfType(ResponseStatusException.class)
				.isThrownBy(() -> controller.authorize(new AuthorizeRequest(UUID.randomUUID())))
				.satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
			assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag restored").isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

}
