package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.kintsugi.lab.fault.catalog.Fault;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link HttpGatewayControl} against a JDK {@link HttpServer} standing in for gateway-mock's admin API. */
class HttpGatewayControlTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private record Received(String method, String path, String contentType, String body) {
	}

	private final List<Received> received = new CopyOnWriteArrayList<>();

	private volatile int answerStatus = 200;

	private volatile long answerDelayMs;

	private HttpServer server;

	private HttpGatewayControl control;

	@BeforeEach
	void startFakeGateway() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		control = new HttpGatewayControl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
				Duration.ofSeconds(2), Duration.ofMillis(500));
	}

	@AfterEach
	void stopFakeGateway() {
		server.stop(0);
	}

	@Test
	void f1PutsTheCatalogLatency() {
		control.activate(Fault.F1);

		assertThat(received).singleElement().satisfies(request -> {
			assertThat(request.method()).isEqualTo("PUT");
			assertThat(request.path()).isEqualTo("/admin/behavior");
			assertThat(request.contentType()).startsWith("application/json");
			assertThat(JSON.readTree(request.body())).isEqualTo(JSON.readTree("{\"latencyMs\":10000}"));
		});
	}

	@Test
	void f2PutsTheCatalogErrorStatusWithoutLatency() {
		control.activate(Fault.F2);

		JsonNode body = JSON.readTree(received.get(0).body());
		assertThat(body).isEqualTo(JSON.readTree("{\"latencyMs\":0,\"errorStatus\":429}"));
	}

	@Test
	void restoreDeletesTheBehaviorForBothGatewayFaults() {
		control.restore(Fault.F1);
		control.restore(Fault.F2);

		assertThat(received).extracting(Received::method, Received::path)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("DELETE", "/admin/behavior"),
					org.assertj.core.groups.Tuple.tuple("DELETE", "/admin/behavior"));
	}

	@Test
	void anErrorAnswerIsAFailedStep() {
		answerStatus = 503;

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F1))
			.withMessageContaining("503");
		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.restore(Fault.F1));
	}

	@Test
	void aGatewayThatDoesNotAnswerInTimeIsAFailedStep() {
		answerDelayMs = 2_000;

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F1));
	}

	@Test
	void anUnreachableGatewayIsAFailedStep() {
		server.stop(0);

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.restore(Fault.F2));
	}

	@Test
	void aComposeFaultIsRefusedWithoutAnyCall() {
		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F3));
		assertThat(received).isEmpty();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Content-Type"), body));
		try {
			Thread.sleep(answerDelayMs);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		byte[] answer = "{\"latencyMs\":0,\"errorStatus\":null}".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(answerStatus, answer.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(answer);
		}
	}

}
