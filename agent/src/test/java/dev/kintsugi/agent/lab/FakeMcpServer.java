package dev.kintsugi.agent.lab;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * mcp-tools as the agent's MCP client sees it: the stateless Streamable HTTP subset it speaks on {@code POST /mcp}
 * ({@code initialize}, the {@code initialized} notification, {@code tools/list}, {@code tools/call}), answering with
 * plain JSON and no session. {@code tools/list} serves the tool list recorded from the lab; each {@code tools/call}
 * answers the text set for that tool, and every call's arguments are kept for the test to read.
 */
final class FakeMcpServer implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.shared();

	private final HttpServer server;

	private final Map<String, String> outputs = new ConcurrentHashMap<>();

	private final Map<String, Boolean> errors = new ConcurrentHashMap<>();

	private final List<JsonNode> calls = new CopyOnWriteArrayList<>();

	private final List<String> methods = new CopyOnWriteArrayList<>();

	private FakeMcpServer(int port) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		this.server.createContext("/mcp", this::handle);
		this.server.start();
	}

	static FakeMcpServer start() throws IOException {
		return new FakeMcpServer(0);
	}

	static FakeMcpServer startOn(int port) throws IOException {
		return new FakeMcpServer(port);
	}

	String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** The text {@code tools/call} returns for {@code tool}; {@code isError} follows its {@code outcome}. */
	FakeMcpServer answer(String tool, String text) {
		this.outputs.put(tool, text);
		this.errors.remove(tool);
		return this;
	}

	/**
	 * The text {@code tools/call} returns for {@code tool}, with {@code isError} as given: a plain-text answer, such as
	 * the MCP server's own input-schema rejection, has no {@code outcome} to follow.
	 */
	FakeMcpServer answer(String tool, String text, boolean isError) {
		this.outputs.put(tool, text);
		this.errors.put(tool, isError);
		return this;
	}

	/** The {@code params} of every {@code tools/call} received, in order. */
	List<JsonNode> calls() {
		return this.calls;
	}

	/** Every JSON-RPC method received, notifications included, in order. */
	List<String> methods() {
		return this.methods;
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
			String method = request.path("method").asString("");
			this.methods.add(method);
			if (!request.has("id")) {
				exchange.sendResponseHeaders(202, -1);
				return;
			}
			ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0").set("id", request.get("id"));
			switch (method) {
				case "initialize" -> response.set("result", JSON.readTree("""
						{"protocolVersion": "%s", "capabilities": {"tools": {"listChanged": false}},
						 "serverInfo": {"name": "mcp-tools", "version": "test"}}"""
					.formatted(request.path("params").path("protocolVersion").asString("2025-11-25"))));
				case "tools/list" -> response.set("result", JSON.readTree(fixture("tools-list.json")));
				case "tools/call" -> {
					JsonNode params = request.path("params");
					this.calls.add(params);
					String name = params.path("name").asString();
					String text = this.outputs.getOrDefault(name,
							"{\"outcome\":\"OK\",\"returned\":0,\"truncated\":false,\"items\":[]}");
					boolean error = this.errors.getOrDefault(name, !text.startsWith("{\"outcome\":\"OK\""));
					ObjectNode result = JSON.createObjectNode().put("isError", error);
					result.putArray("content").addObject().put("type", "text").put("text", text);
					response.set("result", result);
				}
				default -> response.putObject("error").put("code", -32601).put("message", "Method not found");
			}
			byte[] body = JSON.writeValueAsBytes(response);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		}
	}

	/** A tool output recorded from the lab's mcp-tools, in {@code src/test/resources/mcp-outputs}. */
	static String fixture(String name) {
		try (InputStream in = FakeMcpServer.class.getResourceAsStream("/mcp-outputs/" + name)) {
			if (in == null) {
				throw new IllegalArgumentException("no fixture " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
