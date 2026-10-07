package dev.kintsugi.lab.payment.metrics;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.persistence.PaymentRepository;

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

	@Autowired
	private PaymentRepository repository;

	/** The test containers are shared across test classes, so start from an empty table to make the counts exact. */
	@BeforeEach
	void deleteAllPayments() {
		repository.deleteAll();
	}

	@Test
	void scrapeExposesPaymentsByStatusAndHttpServerMetrics() throws Exception {
		repository.save(Payment.create(UUID.randomUUID(), PaymentStatus.FAILED));
		// One finished request, so the HTTP server timer has a series to show.
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());

		// The gauge refreshes asynchronously, so the first scrapes may show NaN or the counts of an earlier test.
		await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> mvc
			.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andExpect(content().string(Matchers.containsString("lab_payments{status=\"FAILED\"} 1.0")))
			.andExpect(content().string(Matchers.containsString("lab_payments{status=\"AUTHORIZED\"} 0.0")))
			.andExpect(content().string(Matchers.containsString("lab_payments{status=\"DECLINED\"} 0.0")))
			.andExpect(content().string(Matchers.containsString("http_server_requests_seconds_count"))));
	}

}
