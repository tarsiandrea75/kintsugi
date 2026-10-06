package dev.kintsugi.lab.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Real HTTP against the embedded Tomcat: while more slow {@code /authorize} requests are in flight than Tomcat's
 * platform pool has threads ({@code server.tomcat.threads.max}, 200 by default), the admin API still answers fast.
 * The fault injector must be able to restore the lab while the lab is broken.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AdminResponsivenessIntegrationTest {

	/** Tomcat's default {@code server.tomcat.threads.max}, plus a margin so every platform thread would be asleep. */
	private static final int SLOW_REQUESTS = 200 + 50;

	private static final Duration LATENCY = Duration.ofSeconds(3);

	/** Time for the slow requests to reach the server before the admin call is measured. */
	private static final Duration SETTLE = Duration.ofMillis(500);

	private static final Duration ADMIN_BOUND = Duration.ofSeconds(1);

	// HTTP/1.1 opens one connection per in-flight request and avoids the JDK client's h2c upgrade attempt.
	private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@LocalServerPort
	private int port;

	@AfterEach
	void resetBehavior() throws Exception {
		client.send(request("/admin/behavior").DELETE().build(), BodyHandlers.discarding());
	}

	@Test
	void adminAnswersQuicklyWhileManyAuthorizeRequestsSleep() throws Exception {
		HttpResponse<String> put = client.send(request("/admin/behavior").header("Content-Type", "application/json")
			.PUT(BodyPublishers.ofString("{\"latencyMs\": " + LATENCY.toMillis() + "}"))
			.build(), BodyHandlers.ofString());
		assertThat(put.statusCode()).isEqualTo(200);

		List<CompletableFuture<HttpResponse<Void>>> slow = new ArrayList<>();
		for (int i = 0; i < SLOW_REQUESTS; i++) {
			slow.add(client.sendAsync(request("/authorize").header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString("{\"orderId\": \"" + UUID.randomUUID() + "\"}"))
				.build(), BodyHandlers.discarding()));
		}
		Thread.sleep(SETTLE);

		assertAnswersWithinBound(request("/admin/behavior").GET());
		assertAnswersWithinBound(request("/admin/behavior").DELETE());

		// The reset does not shorten requests already asleep; wait for them so no load leaks into other tests.
		CompletableFuture.allOf(slow.toArray(CompletableFuture[]::new))
			.get(LATENCY.multipliedBy(3).toMillis(), TimeUnit.MILLISECONDS);
		assertThat(slow).allSatisfy(response -> assertThat(response.join().statusCode()).isEqualTo(200));
	}

	private void assertAnswersWithinBound(HttpRequest.Builder admin) throws Exception {
		long start = System.nanoTime();
		CompletableFuture<HttpResponse<Void>> response = client.sendAsync(admin.build(), BodyHandlers.discarding());
		HttpResponse<Void> answered = response.get(LATENCY.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(answered.statusCode()).isEqualTo(200);
		assertThat(elapsed).isLessThan(ADMIN_BOUND);
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
	}

}
