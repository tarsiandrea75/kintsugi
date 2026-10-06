package dev.kintsugi.lab.payment.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;

/**
 * Full context against PostgreSQL 18.6. {@link AutoConfigureMetrics} is needed because Spring Boot disables metrics
 * export in tests by default, which would leave {@code /actuator/prometheus} unregistered.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
class PrometheusEndpointIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void scrapeExposesHttpServerMetrics() throws Exception {
		// One finished request, so the HTTP server timer has a series to show.
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());

		mvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().string(Matchers.containsString("http_server_requests_seconds_count")));
	}

}
