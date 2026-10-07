package dev.kintsugi.lab.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import dev.kintsugi.lab.inventory.TestcontainersConfiguration;

/**
 * Fault F4 in miniature, over real HTTP: a pool of one connection, a 250 ms connection timeout and a 1 s reservation
 * delay. The first reservation holds the only connection for 1 s, so the second one cannot get a connection within
 * 250 ms. A real server is used because MockMvc would rethrow an unhandled exception instead of answering it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.datasource.hikari.maximum-pool-size=1",
				"spring.datasource.hikari.connection-timeout=250", "kintsugi.inventory.reservation-delay=1s" })
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PoolExhaustionIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	@Timeout(10)
	void secondConcurrentReservationGetsInternalServerError(CapturedOutput output) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

		long start = System.nanoTime();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		List<CompletableFuture<HttpResponse<String>>> calls = List.of(reserve(client, first), reserve(client, second));
		List<Integer> statuses = calls.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).sorted().toList();
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(statuses).containsExactly(201, 500);
		// Assumes the two requests overlap: both are sent at once and the first holds the only connection for 1 s,
		// far longer than it takes the second to arrive. If the second started after the first had finished, both
		// would answer 201. The 5 s bound shows the 500 came from the 250 ms timeout, not from waiting out the delay.
		assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
		assertThat(output.getAll().lines()
			.filter(line -> line.contains(" WARN ") && line.contains("No database connection available")
					&& line.contains("Connection is not available, request timed out after")))
			.singleElement()
			.satisfies(line -> assertThat(line).contains("event=RESERVATION_ATTEMPT")
				.contains("outcome=DATABASE_UNAVAILABLE")
				.containsAnyOf("order=" + first, "order=" + second));
	}

	private CompletableFuture<HttpResponse<String>> reserve(HttpClient client, UUID orderId) {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/reservations"))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(5))
			.POST(HttpRequest.BodyPublishers.ofString("""
					{"orderId": "%s", "items": [{"sku": "SKU-4", "quantity": 1}]}
					""".formatted(orderId)))
			.build();
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
	}

}
