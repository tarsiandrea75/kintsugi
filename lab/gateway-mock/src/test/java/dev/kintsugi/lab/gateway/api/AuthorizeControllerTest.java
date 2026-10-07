package dev.kintsugi.lab.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

	@Autowired
	private BehaviorStore store;

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
	@CsvSource({ "429, rate limited", "500, internal error", "503, unavailable" })
	void configuredErrorStatusIsReturnedWithAnErrorBody(int errorStatus, String error) throws Exception {
		store.set(new GatewayBehavior(0, errorStatus));
		try {
			mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content("""
					{"orderId": "11111111-1111-1111-1111-111111111111"}
					"""))
				.andExpect(status().is(errorStatus))
				.andExpect(content().json("""
						{"error": "%s"}
						""".formatted(error), true));
		}
		finally {
			store.reset();
		}
		assertThat(registry.get(AuthorizeController.INFLIGHT_GAUGE).gauge().value()).isZero();
	}

	@Test
	void errorStatusIsAnsweredOnlyAfterTheLatency() {
		BehaviorStore slowStore = new BehaviorStore();
		slowStore.set(new GatewayBehavior(200, 429));
		AuthorizeController controller = new AuthorizeController(slowStore, new SimpleMeterRegistry());

		long start = System.nanoTime();
		var response = controller.authorize(new AuthorizeRequest(UUID.randomUUID()));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(200));
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
