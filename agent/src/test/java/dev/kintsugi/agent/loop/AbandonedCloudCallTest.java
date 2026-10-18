package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.LabTools;

/**
 * A cloud call the loop abandons at its deadline must not keep running (agent spec, "Routes"). The Anthropic SDK's
 * OkHttp call ignores the interruption the loop sends its worker, and the client's own timeout is the longest call
 * deadline, so without a per-call timeout an abandoned {@code ACT} call would hold its connection, and the provider
 * would go on writing the answer, up to the report's deadline. The agent runs with the auto-configured Anthropic client
 * against a stub that never answers; the stub records when the client closes the connection.
 */
@SpringBootTest(properties = { "kintsugi.agent.route=cloud", "spring.ai.anthropic.api-key=test-only-not-a-key" })
class AbandonedCloudCallTest {

	private static final HangingServer SERVER = HangingServer.start();

	@TestBean
	LabTools labTools;

	@Autowired
	AgentLoop loop;

	static LabTools labTools() {
		return new FakeLabTools();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
		registry.add("spring.ai.anthropic.base-url", SERVER::url);
		// Long enough for the client's first request, which loads and builds the SDK's classes, to reach the stub.
		registry.add("kintsugi.agent.cloud.call-timeout.act", () -> "2s");
		registry.add("kintsugi.agent.cloud.call-timeout.report", () -> "2s");
		// The client's own timeout, far beyond the call's deadline: only the call's own timeout can end it in the test.
		registry.add("spring.ai.anthropic.timeout", () -> "30s");
	}

	@BeforeEach
	void empty() {
		AgentDatabase.empty();
	}

	@AfterAll
	static void stop() throws IOException {
		SERVER.close();
	}

	@Test
	void aCloudCallPastItsDeadlineClosesItsConnection() throws Exception {
		UUID id = UUID.randomUUID();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, CAST(? AS jsonb), 'RUNNING', now())")
			.params(id, "{\"symptom\":\"Checkout orders stay pending for a long time\",\"sinceMinutes\":15}")
			.update();

		long start = System.nanoTime();
		assertThat(this.loop.run(id)).isEqualTo(IncidentStatus.TIMED_OUT);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(8));
		assertThat(SERVER.requests()).isEqualTo(1);
		// The client closes the connection within the per-call grace after the deadline, long before its 30 s timeout.
		assertThat(SERVER.awaitClosed(Duration.ofSeconds(5))).as("the abandoned request's connection is closed").isTrue();
	}

	/** A stub that reads each connection's bytes and never answers, until the client closes the connection. */
	private static final class HangingServer implements AutoCloseable {

		private final ServerSocket server;

		private final AtomicInteger requests = new AtomicInteger();

		private final CountDownLatch closed = new CountDownLatch(1);

		private HangingServer(ServerSocket server) {
			this.server = server;
			Thread.ofPlatform().daemon().name("hanging-anthropic").start(this::accept);
		}

		static HangingServer start() {
			try {
				return new HangingServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
			}
			catch (IOException failed) {
				throw new UncheckedIOException(failed);
			}
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getLocalPort();
		}

		int requests() {
			return this.requests.get();
		}

		boolean awaitClosed(Duration limit) throws InterruptedException {
			return this.closed.await(limit.toMillis(), TimeUnit.MILLISECONDS);
		}

		private void accept() {
			while (!this.server.isClosed()) {
				try {
					Socket socket = this.server.accept();
					Thread.ofPlatform().daemon().start(() -> hang(socket));
				}
				catch (IOException gone) {
					return;
				}
			}
		}

		/** Counts the request once its first byte arrives, then reads until the client closes the connection. */
		private void hang(Socket socket) {
			try (socket; InputStream in = socket.getInputStream()) {
				if (in.read() < 0) {
					return;
				}
				this.requests.incrementAndGet();
				while (in.read() >= 0) {
					// discard
				}
			}
			catch (IOException reset) {
				// a reset is a close too
			}
			this.closed.countDown();
		}

		@Override
		public void close() throws IOException {
			this.server.close();
		}

	}

}
