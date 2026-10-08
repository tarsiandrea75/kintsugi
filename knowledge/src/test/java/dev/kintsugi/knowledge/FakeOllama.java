package dev.kintsugi.knowledge;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for Ollama, on the JDK's HTTP server like mcp-tools' {@code FakeHttpBackend}. {@code GET /api/tags} lists
 * one model with a fixed digest; {@code POST /api/embed} answers each input with a deterministic vector derived from
 * its text, records every input, and can fail with HTTP 500 on a chosen call.
 */
final class FakeOllama implements AutoCloseable {

	static final String DIGEST = "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f";

	private static final JsonMapper JSON = JsonMapper.shared();

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final List<List<String>> embedCalls = new CopyOnWriteArrayList<>();

	private final AtomicInteger tagCalls = new AtomicInteger();

	private volatile String listedModel = "nomic-embed-text:latest";

	private volatile String digest = DIGEST;

	private volatile int dimensions = 768;

	private volatile int failOnEmbedCall = 0;

	private FakeOllama(HttpServer server) {
		this.server = server;
	}

	static FakeOllama start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			FakeOllama fake = new FakeOllama(server);
			server.createContext("/api/tags", fake::tags);
			server.createContext("/api/embed", fake::embed);
			server.setExecutor(fake.executor);
			server.start();
			return fake;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not start the fake Ollama", ex);
		}
	}

	String baseUrl() {
		return "http://localhost:" + this.server.getAddress().getPort();
	}

	/** Restores the defaults and forgets every recorded call. */
	void reset() {
		this.listedModel = "nomic-embed-text:latest";
		this.digest = DIGEST;
		this.dimensions = 768;
		this.failOnEmbedCall = 0;
		this.embedCalls.clear();
		this.tagCalls.set(0);
	}

	void lists(String model, String digest) {
		this.listedModel = model;
		this.digest = digest;
	}

	void embedsWithDimensions(int dimensions) {
		this.dimensions = dimensions;
	}

	/** Answers HTTP 500 on the {@code call}-th embed call (1-based) of this test. */
	void failOnEmbedCall(int call) {
		this.failOnEmbedCall = call;
	}

	/** Inputs of every embed call so far, one list per call. */
	List<List<String>> embedCalls() {
		return List.copyOf(this.embedCalls);
	}

	List<String> embeddedInputs() {
		return this.embedCalls.stream().flatMap(List::stream).toList();
	}

	int tagCalls() {
		return this.tagCalls.get();
	}

	private void tags(HttpExchange exchange) throws IOException {
		this.tagCalls.incrementAndGet();
		reply(exchange, 200, Map.of("models", List.of(Map.of("name", this.listedModel, "model", this.listedModel,
				"size", 274302450, "digest", this.digest))));
	}

	private void embed(HttpExchange exchange) throws IOException {
		JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
		List<String> inputs = new ArrayList<>();
		request.get("input").forEach(input -> inputs.add(input.asString()));
		this.embedCalls.add(List.copyOf(inputs));
		if (this.embedCalls.size() == this.failOnEmbedCall) {
			reply(exchange, 500, Map.of("error", "model runner stopped"));
			return;
		}
		List<float[]> embeddings = inputs.stream().map(this::vector).toList();
		reply(exchange, 200, Map.of("model", request.get("model").asString(), "embeddings", embeddings));
	}

	private float[] vector(String text) {
		Random random = new Random(text.hashCode());
		float[] vector = new float[this.dimensions];
		for (int i = 0; i < vector.length; i++) {
			vector[i] = random.nextFloat() - 0.5f;
		}
		return vector;
	}

	private static void reply(HttpExchange exchange, int code, Object body) throws IOException {
		try (exchange) {
			byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(code, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	@Override
	public void close() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

}
