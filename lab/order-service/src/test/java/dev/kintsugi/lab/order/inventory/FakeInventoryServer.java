package dev.kintsugi.lab.order.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for inventory-service in tests, built on the JDK's HTTP server like {@code FakePaymentServer}. It listens
 * on an ephemeral loopback port and answers {@code POST /reservations} with whatever the test set last; it can also
 * wait before answering, to trigger the client's read timeout.
 */
public final class FakeInventoryServer implements AutoCloseable {

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final AtomicInteger requestCount = new AtomicInteger();

	private volatile Reply reply = Reply.status(201, "RESERVED");

	private volatile RecordedRequest lastRequest;

	private FakeInventoryServer(HttpServer server) {
		this.server = server;
	}

	public static FakeInventoryServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			FakeInventoryServer fake = new FakeInventoryServer(server);
			server.createContext("/reservations", fake::handle);
			server.setExecutor(fake.executor);
			server.start();
			return fake;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not start the fake inventory server", ex);
		}
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	/** Answers with HTTP {@code code} and a reservation whose status is {@code reservationStatus}. */
	public void replyWith(int code, String reservationStatus) {
		reply = Reply.status(code, reservationStatus);
	}

	/** Answers with HTTP {@code code} and {@code body} as it is. */
	public void replyWithBody(int code, String body) {
		reply = new Reply(code, body, Duration.ZERO);
	}

	/** Answers {@code RESERVED}, but only after {@code delay}. */
	public void replyAfter(Duration delay) {
		reply = new Reply(201, Reply.status(201, "RESERVED").body(), delay);
	}

	public RecordedRequest lastRequest() {
		return lastRequest;
	}

	/** How many requests reached the server since it started. */
	public int requestCount() {
		return requestCount.get();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange; InputStream in = exchange.getRequestBody()) {
			lastRequest = new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					new String(in.readAllBytes(), StandardCharsets.UTF_8));
			requestCount.incrementAndGet();
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

		static Reply status(int code, String reservationStatus) {
			return new Reply(code, """
					{"orderId": "44444444-4444-4444-4444-444444444444", "status": "%s"}""".formatted(reservationStatus),
					Duration.ZERO);
		}

	}

}
