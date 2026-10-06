package dev.kintsugi.lab.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.kintsugi.lab.gateway.behavior.BehaviorStore;
import dev.kintsugi.lab.gateway.behavior.GatewayBehavior;

@WebMvcTest(AdminBehaviorController.class)
@Import(BehaviorStore.class)
class AdminBehaviorControllerTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private BehaviorStore store;

	// The store is a singleton in a cached test context, so each test starts from the default behavior.
	@BeforeEach
	void resetStore() {
		store.reset();
	}

	@Test
	void defaultBehaviorIsReturned() throws Exception {
		mvc.perform(get("/admin/behavior"))
			.andExpect(status().isOk())
			.andExpect(content().json("""
					{"latencyMs": 0}
					""", true));
	}

	@Test
	void latencyIsSetAndReadBack() throws Exception {
		putLatency("{\"latencyMs\": 10000}")
			.andExpect(status().isOk())
			.andExpect(content().json("""
					{"latencyMs": 10000}
					""", true));

		mvc.perform(get("/admin/behavior"))
			.andExpect(status().isOk())
			.andExpect(content().json("""
					{"latencyMs": 10000}
					""", true));
		assertThat(store.get()).isEqualTo(new GatewayBehavior(10_000));
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, GatewayBehavior.MAX_LATENCY_MS })
	void boundsAreAccepted(long latencyMs) throws Exception {
		putLatency("{\"latencyMs\": " + latencyMs + "}").andExpect(status().isOk());

		assertThat(store.get().latencyMs()).isEqualTo(latencyMs);
	}

	@Test
	void deleteResetsTheLatency() throws Exception {
		store.set(new GatewayBehavior(10_000));

		mvc.perform(delete("/admin/behavior"))
			.andExpect(status().isOk())
			.andExpect(content().json("""
					{"latencyMs": 0}
					""", true));
		assertThat(store.get()).isEqualTo(GatewayBehavior.DEFAULT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"latencyMs\": null}", "{\"latencyMs\": -1}", "{\"latencyMs\": 60001}",
			"{\"latencyMs\": \"slow\"}", "{\"latencyMs\": " })
	void invalidLatencyIsRejectedAndKeepsTheBehavior(String body) throws Exception {
		GatewayBehavior before = store.set(new GatewayBehavior(500));

		putLatency(body).andExpect(status().isBadRequest());

		assertThat(store.get()).isEqualTo(before);
	}

	private ResultActions putLatency(String body) throws Exception {
		return mvc.perform(put("/admin/behavior").contentType(MediaType.APPLICATION_JSON).content(body));
	}

}
