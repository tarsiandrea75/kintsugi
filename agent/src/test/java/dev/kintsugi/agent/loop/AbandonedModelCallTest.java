package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.LabTools;

/**
 * A model call the loop abandons at its deadline must not keep running in Ollama (agent spec, "Budgets", "An abandoned
 * call is aborted"). Ollama serves one request at a time and stops generating only when its client goes away, so the
 * loop must close the HTTP connection of a call it stops waiting for, and send each request in a form that lets Ollama
 * notice. The agent runs as the web profile wires it, with
 * the auto-configured Ollama client, against a stub server that answers the warm-up and then never answers; the stub
 * records when the client closes the hanging connection.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AbandonedModelCallTest {

	private static final HangingOllama OLLAMA = HangingOllama.start();

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
		registry.add("spring.ai.ollama.base-url", OLLAMA::url);
		registry.add("kintsugi.agent.model.call-timeout.act", () -> "500ms");
		registry.add("kintsugi.agent.model.call-timeout.report", () -> "500ms");
		// The HTTP read timeout is the longer of the call's and the warm-up's: 30 s, far beyond the call's deadline,
		// so only the loop can end the hanging request within the test.
		registry.add("kintsugi.agent.loop.warm-up-timeout", () -> "30s");
	}

	@BeforeEach
	void empty() {
		AgentDatabase.empty();
	}

	@AfterAll
	static void stop() throws IOException {
		OLLAMA.close();
	}

	@Test
	void aModelCallPastItsDeadlineClosesItsHttpConnection() throws Exception {
		UUID id = UUID.randomUUID();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, CAST(? AS jsonb), 'RUNNING', now())")
			.params(id, "{\"symptom\":\"Checkout orders stay pending for a long time\",\"sinceMinutes\":15}")
			.update();

		long start = System.nanoTime();
		assertThat(this.loop.run(id)).isEqualTo(IncidentStatus.TIMED_OUT);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
		assertThat(OLLAMA.hangingRequests()).isEqualTo(1);
		// Ollama's server (Go net/http) watches for its client going away only once it has read the request body to
		// its end. Its JSON decoder stops at the end of the object, so with a chunked body whose last chunk arrives in
		// a later packet it never does, and an abandoned call runs to its end: measured on the lab, 2026-10-14. A body
		// with a Content-Length ends with its last byte, so the request must carry one.
		assertThat(OLLAMA.hangingHead()).contains("content-length:").doesNotContain("transfer-encoding: chunked");
		// The client closes the connection soon after the deadline, long before the 30 s read timeout.
		assertThat(OLLAMA.awaitHangingClosed(Duration.ofSeconds(5))).as("the abandoned request's connection is closed")
			.isTrue();
	}

	/**
	 * A one-thread-per-connection HTTP/1.1 stub of Ollama's {@code /api/chat}: it answers the warm-up at once, and
	 * holds every other request open without answering until the client closes the connection.
	 */
	private static final class HangingOllama implements AutoCloseable {

		private static final String WARM_UP_ANSWER = "{\"model\":\"granite4.1:8b\",\"created_at\":\"2026-10-14T00:00:00Z\","
				+ "\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true,\"done_reason\":\"stop\","
				+ "\"total_duration\":1,\"load_duration\":1,\"prompt_eval_count\":1,\"prompt_eval_duration\":1,"
				+ "\"eval_count\":1,\"eval_duration\":1}";

		private final ServerSocket server;

		private final AtomicInteger hanging = new AtomicInteger();

		private volatile String hangingHead = "";

		private final CountDownLatch hangingClosed = new CountDownLatch(1);

		private HangingOllama(ServerSocket server) {
			this.server = server;
			Thread.ofPlatform().daemon().name("hanging-ollama").start(this::accept);
		}

		static HangingOllama start() {
			try {
				return new HangingOllama(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
			}
			catch (IOException failed) {
				throw new UncheckedIOException(failed);
			}
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getLocalPort();
		}

		int hangingRequests() {
			return this.hanging.get();
		}

		/** The request line and headers of the last request left hanging, in lower case. */
		String hangingHead() {
			return this.hangingHead;
		}

		boolean awaitHangingClosed(Duration limit) throws InterruptedException {
			return this.hangingClosed.await(limit.toMillis(), TimeUnit.MILLISECONDS);
		}

		private void accept() {
			while (!this.server.isClosed()) {
				try {
					Socket socket = this.server.accept();
					Thread.ofPlatform().daemon().start(() -> serve(socket));
				}
				catch (IOException closed) {
					return;
				}
			}
		}

		/** Serves the requests of one connection in turn, as HTTP/1.1 keep-alive allows. */
		private void serve(Socket socket) {
			try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
				while (true) {
					String request = readRequest(in);
					if (request == null) {
						return;
					}
					if (request.contains(Prompts.WARM_UP)) {
						byte[] body = WARM_UP_ANSWER.getBytes(StandardCharsets.UTF_8);
						out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
								+ "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
						out.write(body);
						out.flush();
						continue;
					}
					this.hanging.incrementAndGet();
					this.hangingHead = request.substring(0, request.indexOf("\r\n\r\n")).toLowerCase(Locale.ROOT);
					// Never answer: wait until the client closes the connection (read returns -1 or fails).
					try {
						while (in.read() >= 0) {
							// discard
						}
					}
					catch (IOException reset) {
						// a reset is a close too
					}
					this.hangingClosed.countDown();
					return;
				}
			}
			catch (IOException gone) {
				// the client went away between requests
			}
		}

		/** One request's head and body as text, or null at the end of the stream. */
		private static String readRequest(InputStream in) throws IOException {
			ByteArrayOutputStream head = new ByteArrayOutputStream();
			int matched = 0;
			int next;
			while (matched < 4 && (next = in.read()) >= 0) {
				head.write(next);
				matched = (next == "\r\n\r\n".charAt(matched)) ? matched + 1 : (next == '\r' ? 1 : 0);
			}
			if (matched < 4) {
				return null;
			}
			String headText = head.toString(StandardCharsets.US_ASCII);
			String lower = headText.toLowerCase(Locale.ROOT);
			byte[] body;
			int at = lower.indexOf("content-length:");
			if (at >= 0) {
				int end = lower.indexOf("\r\n", at);
				int length = Integer.parseInt(headText.substring(at + 15, end).strip());
				body = in.readNBytes(length);
			}
			else if (lower.contains("transfer-encoding: chunked")) {
				body = readChunked(in);
			}
			else {
				body = new byte[0];
			}
			return headText + new String(body, StandardCharsets.UTF_8);
		}

		private static byte[] readChunked(InputStream in) throws IOException {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			while (true) {
				String sizeLine = readLine(in);
				int size = Integer.parseInt(sizeLine.split(";")[0].strip(), 16);
				if (size == 0) {
					readLine(in);
					return body.toByteArray();
				}
				body.write(in.readNBytes(size));
				readLine(in);
			}
		}

		private static String readLine(InputStream in) throws IOException {
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int next;
			while ((next = in.read()) >= 0 && next != '\n') {
				if (next != '\r') {
					line.write(next);
				}
			}
			return line.toString(StandardCharsets.US_ASCII);
		}

		@Override
		public void close() throws IOException {
			this.server.close();
		}

	}

}
