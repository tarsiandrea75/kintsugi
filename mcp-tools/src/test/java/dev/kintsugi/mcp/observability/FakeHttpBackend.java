package dev.kintsugi.mcp.observability;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for Loki or Prometheus in tests, built on the JDK's HTTP server like {@code FakePaymentServer} in
 * order-service. It listens on an ephemeral loopback port, records every request with its decoded query parameters,
 * and answers whatever the test set last; it can also wait before answering, to trigger the client's read timeout.
 */
public final class FakeHttpBackend implements AutoCloseable {

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

	private volatile Reply reply = new Reply(200, "{\"status\":\"success\",\"data\":{\"result\":[]}}", Duration.ZERO);

	private FakeHttpBackend(HttpServer server) {
		this.server = server;
	}

	public static FakeHttpBackend start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			FakeHttpBackend fake = new FakeHttpBackend(server);
			server.createContext("/", fake::handle);
			server.setExecutor(fake.executor);
			server.start();
			return fake;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not start the fake backend", ex);
		}
	}

	public String baseUrl() {
		return "http://localhost:" + this.server.getAddress().getPort();
	}

	/** Answers with HTTP {@code code} and {@code body} as it is. */
	public void replyWith(int code, String body) {
		this.reply = new Reply(code, body, Duration.ZERO);
	}

	/** Answers 200 with {@code body}, but only after {@code delay}. */
	public void replyAfter(Duration delay, String body) {
		this.reply = new Reply(200, body, delay);
	}

	public int calls() {
		return this.requests.size();
	}

	public RecordedRequest lastRequest() {
		return this.requests.isEmpty() ? null : this.requests.get(this.requests.size() - 1);
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			this.requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					decode(exchange.getRequestURI().getRawQuery())));
			Reply current = this.reply;
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

	private static Map<String, String> decode(String rawQuery) {
		Map<String, String> params = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return params;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			String name = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			params.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return params;
	}

	@Override
	public void close() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	public record RecordedRequest(String method, String path, Map<String, String> params) {
	}

	private record Reply(int code, String body, Duration delay) {
	}

}
