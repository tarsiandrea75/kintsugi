package dev.kintsugi.lab.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Full application context: the latency set through the admin API really delays {@code POST /authorize}. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizeLatencyIntegrationTest {

	private static final Duration LATENCY = Duration.ofMillis(300);

	@Autowired
	private MockMvc mvc;

	@AfterEach
	void resetBehavior() throws Exception {
		mvc.perform(delete("/admin/behavior")).andExpect(status().isOk());
	}

	@Test
	void authorizeWaitsForTheConfiguredLatency() throws Exception {
		mvc.perform(put("/admin/behavior").contentType(MediaType.APPLICATION_JSON).content("""
				{"latencyMs": %d}
				""".formatted(LATENCY.toMillis()))).andExpect(status().isOk());

		long start = System.nanoTime();
		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "22222222-2222-2222-2222-222222222222"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).isGreaterThanOrEqualTo(LATENCY);
	}

}
