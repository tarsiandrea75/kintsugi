package dev.kintsugi.lab.payment.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for gateway-mock in tests, built on the JDK's HTTP server so no test library is needed. It listens on an
 * ephemeral loopback port and answers {@code POST /authorize} with whatever the test set last; it can also wait
 * before answering, to trigger the client's read timeout.
 */
public final class FakeGatewayServer implements AutoCloseable {

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private volatile Reply reply = Reply.status(200, "AUTHORIZED");

	private volatile RecordedRequest lastRequest;

	private FakeGatewayServer(HttpServer server) {
		this.server = server;
	}

	public static FakeGatewayServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			FakeGatewayServer fake = new FakeGatewayServer(server);
			server.createContext("/authorize", fake::handle);
			server.setExecutor(fake.executor);
			server.start();
			return fake;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not start the fake gateway server", ex);
		}
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	/** Answers with HTTP {@code code} and an authorization whose status is {@code status}. */
	public void replyWith(int code, String status) {
		reply = Reply.status(code, status);
	}

	/** Answers with HTTP {@code code} and {@code body} as it is. */
	public void replyWithBody(int code, String body) {
		reply = new Reply(code, body, Duration.ZERO);
	}

	/** Answers {@code AUTHORIZED}, but only after {@code delay}. */
	public void replyAfter(Duration delay) {
		reply = new Reply(200, Reply.status(200, "AUTHORIZED").body(), delay);
	}

	public RecordedRequest lastRequest() {
		return lastRequest;
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange; InputStream in = exchange.getRequestBody()) {
			lastRequest = new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					new String(in.readAllBytes(), StandardCharsets.UTF_8));
			Reply current = reply;
			Thread.sleep(current.delay());
			byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(current.code(), body.length == 0 ? -1 : body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

	public record RecordedRequest(String method, String path, String contentType, String body) {
	}

	private record Reply(int code, String body, Duration delay) {

		static Reply status(int code, String status) {
			return new Reply(code, """
					{"status": "%s"}""".formatted(status), Duration.ZERO);
		}

	}

}
