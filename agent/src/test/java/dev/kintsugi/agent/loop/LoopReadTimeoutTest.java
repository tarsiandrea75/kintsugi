package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The loop's own HTTP read timeout (agent spec, "Shared rules"): the longer of one model call and the warm-up, applied
 * to the RestClient that Ollama's client is built from; the demo profile keeps spring.http.clients.read-timeout.
 */
class LoopReadTimeoutTest {

	private HttpServer server;

	@BeforeEach
	void slowServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(1500);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			byte[] body = "late".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.server.start();
	}

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	@Test
	void theReadTimeoutIsTheLongerOfACallAndTheWarmUp() {
		assertThat(LoopConfig.readTimeout(route(Duration.ofSeconds(180)), loop(Duration.ofSeconds(300))))
			.isEqualTo(Duration.ofSeconds(300));
		assertThat(LoopConfig.readTimeout(route(Duration.ofSeconds(400)), loop(Duration.ofSeconds(300))))
			.isEqualTo(Duration.ofSeconds(400));
	}

	@Test
	void theCustomizerCutsARequestAtTheLoopsReadTimeout() {
		RestClient.Builder builder = RestClient.builder();
		new LoopConfig().loopReadTimeout(route(Duration.ofMillis(300)), loop(Duration.ofMillis(200))).customize(builder);
		RestClient client = builder.baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort()).build();

		assertThatExceptionOfType(ResourceAccessException.class)
			.isThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class));
	}

	@Test
	void aRequestWithinTheReadTimeoutAnswers() {
		RestClient.Builder builder = RestClient.builder();
		new LoopConfig().loopReadTimeout(route(Duration.ofSeconds(5)), loop(Duration.ofSeconds(1))).customize(builder);
		RestClient client = builder.baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort()).build();

		assertThat(client.get().uri("/slow").retrieve().body(String.class)).isEqualTo("late");
	}

	private static ModelRouteProperties route(Duration callTimeout) {
		return new ModelRouteProperties("granite4.1:8b", 0, 8192, callTimeout);
	}

	private static LoopSettings loop(Duration warmUpTimeout) {
		return new LoopSettings(12, Duration.ofMinutes(30), warmUpTimeout);
	}

}
