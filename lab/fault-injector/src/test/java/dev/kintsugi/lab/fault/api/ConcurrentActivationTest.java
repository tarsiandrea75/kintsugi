package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.control.FakeLabControlConfiguration;

/**
 * Two {@code POST /faults} released at the same instant over real HTTP: the partial unique index lets exactly one
 * insert through, so exactly one answers 201, the other 409, and the lab is activated once.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, FakeLabControlConfiguration.class })
class ConcurrentActivationTest {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeLabControl lab;

	@BeforeEach
	void emptyLabAndTables() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
		lab.reset();
	}

	@RepeatedTest(5)
	void twoSimultaneousActivationsGiveExactlyOne201AndOne409() throws Exception {
		CyclicBarrier start = new CyclicBarrier(2);
		List<Future<Integer>> answers = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			for (String fault : List.of("F1", "F2")) {
				answers.add(pool.submit(() -> {
					HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/faults"))
						.header("Content-Type", "application/json")
						.timeout(Duration.ofSeconds(10))
						.POST(HttpRequest.BodyPublishers.ofString("{\"fault\": \"" + fault + "\"}"))
						.build();
					start.await(5, TimeUnit.SECONDS);
					return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
				}));
			}
		}

		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> answer : answers) {
			statuses.add(answer.get());
		}
		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(jdbc.sql("SELECT status FROM injections").query(String.class).list()).containsExactly("ACTIVE");
		assertThat(lab.calls()).hasSize(1);
	}

}
