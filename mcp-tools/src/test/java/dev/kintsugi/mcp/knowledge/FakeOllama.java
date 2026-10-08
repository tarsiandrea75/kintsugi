package dev.kintsugi.mcp.knowledge;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for Ollama, as in the {@code knowledge} module's tests: {@code GET /api/tags} lists one model with a
 * digest, and {@code POST /api/embed} answers each input with the vector the test chose for it, or a unit vector along
 * the first axis. It records every input and can answer HTTP 500 instead, answer {@code /api/tags} without a model
 * list, or accept a request and never answer it.
 */
public final class FakeOllama implements AutoCloseable {

	public static final String DIGEST = "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f";

	private static final JsonMapper JSON = JsonMapper.shared();

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final List<String> embedded = new CopyOnWriteArrayList<>();

	private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

	private final AtomicInteger tagCalls = new AtomicInteger();

	private volatile String listedModel = "nomic-embed-text:latest";

	private volatile String digest = DIGEST;

	private volatile int dimensions = 768;

	private volatile boolean failing;

	private volatile boolean noModelList;

	private final Set<String> stalled = ConcurrentHashMap.newKeySet();

	private FakeOllama(HttpServer server) {
		this.server = server;
	}

	public static FakeOllama start() {
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

	public String baseUrl() {
		return "http://localhost:" + this.server.getAddress().getPort();
	}

	/** Restores the defaults and forgets every recorded call and chosen vector. */
	public void reset() {
		this.listedModel = "nomic-embed-text:latest";
		this.digest = DIGEST;
		this.dimensions = 768;
		this.failing = false;
		this.noModelList = false;
		this.stalled.clear();
		this.embedded.clear();
		this.vectors.clear();
		this.tagCalls.set(0);
	}

	public void lists(String model, String digest) {
		this.listedModel = model;
		this.digest = digest;
	}

	/** Answers {@code input}, the whole text sent (prefix included), with {@code vector}. */
	public void embedsAs(String input, float[] vector) {
		this.vectors.put(input, vector);
	}

	/** Inputs without a chosen vector get a unit vector of this many dimensions. */
	public void embedsWithDimensions(int dimensions) {
		this.dimensions = dimensions;
	}

	/** Answers HTTP 500 on every call. */
	public void failing(boolean failing) {
		this.failing = failing;
	}

	/** Answers {@code GET /api/tags} with {@code {}}, a body without {@code models}. */
	public void listsNoModelList() {
		this.noModelList = true;
	}

	/** Reads every request on {@code path} ({@code /api/tags} or {@code /api/embed}) and never answers it. */
	public void stallsOn(String path) {
		this.stalled.add(path);
	}

	public List<String> embeddedInputs() {
		return List.copyOf(this.embedded);
	}

	public int tagCalls() {
		return this.tagCalls.get();
	}

	private void tags(HttpExchange exchange) throws IOException {
		this.tagCalls.incrementAndGet();
		stallIfAsked(exchange);
		if (this.noModelList) {
			reply(exchange, 200, Map.of());
			return;
		}
		if (this.failing) {
			reply(exchange, 500, Map.of("error", "server stopped"));
			return;
		}
		reply(exchange, 200, Map.of("models", List.of(Map.of("name", this.listedModel, "model", this.listedModel,
				"size", 274302450, "digest", this.digest))));
	}

	private void embed(HttpExchange exchange) throws IOException {
		JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
		List<String> inputs = new ArrayList<>();
		request.get("input").forEach(input -> inputs.add(input.asString()));
		this.embedded.addAll(inputs);
		stallIfAsked(exchange);
		if (this.failing) {
			reply(exchange, 500, Map.of("error", "model runner stopped"));
			return;
		}
		List<float[]> embeddings = inputs.stream().map(input -> this.vectors.getOrDefault(input, fallback())).toList();
		reply(exchange, 200, Map.of("model", request.get("model").asString(), "embeddings", embeddings));
	}

	/** Holds the exchange until the server stops, which interrupts this virtual thread. */
	private void stallIfAsked(HttpExchange exchange) {
		if (!this.stalled.contains(exchange.getHttpContext().getPath())) {
			return;
		}
		try {
			Thread.sleep(Duration.ofMinutes(5));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private float[] fallback() {
		float[] vector = new float[this.dimensions];
		vector[0] = 1;
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
