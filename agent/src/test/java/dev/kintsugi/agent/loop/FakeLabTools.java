package dev.kintsugi.agent.loop;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.LabToolsUnavailableException;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.lab.ToolResult;

/**
 * The lab as the loop sees it, answering each tool with an output recorded from the lab's mcp-tools
 * ({@code src/test/resources/mcp-outputs}) unless the test sets another, and keeping every call.
 */
final class FakeLabTools implements LabTools {

	static final Map<String, String> DEFAULT_FIXTURES = Map.of("status_summary", "status_summary-orders.json",
			"query_metric", "query_metric-error_rate.json", "service_restarts", "service_restarts.json", "search_logs",
			"search_logs-order.json", "count_logs", "count_logs.json", "recent_events", "recent_events-order.json",
			"get_order_trace", "get_order_trace.json", "search_runbooks", "search_runbooks.json");

	private final Map<String, String> outputs = new ConcurrentHashMap<>();

	private final List<String> calls = new CopyOnWriteArrayList<>();

	private volatile RuntimeException unavailable;

	private volatile Error broken;

	FakeLabTools() {
		DEFAULT_FIXTURES.forEach((tool, fixture) -> this.outputs.put(tool, fixture(fixture)));
	}

	/** Answers {@code tool} with {@code output} from now on. */
	FakeLabTools answer(String tool, String output) {
		this.outputs.put(tool, output);
		return this;
	}

	/** Makes {@link #toolsFor} fail, as when mcp-tools cannot list its tools. */
	FakeLabTools unavailable() {
		this.unavailable = new LabToolsUnavailableException("mcp-tools did not list its tools: connection refused",
				null);
		return this;
	}

	/** Makes {@link #call} throw {@code error}, as a broken class path or an exhausted JVM would. */
	FakeLabTools breaks(Error error) {
		this.broken = error;
		return this;
	}

	@Override
	public ToolResult call(String tool, String jsonArgs) {
		if (this.broken != null) {
			throw this.broken;
		}
		if (!Phase.isLabTool(tool)) {
			throw new IllegalArgumentException("not an mcp-tools tool: " + tool);
		}
		this.calls.add(tool);
		String output = this.outputs.get(tool);
		String outcome = JsonMapper.shared().readTree(output).path("outcome").asString();
		return new ToolResult(tool, jsonArgs == null || jsonArgs.isBlank() ? "{}" : jsonArgs, output,
				ToolOutcome.valueOf(outcome), 3);
	}

	@Override
	public List<ToolCallback> toolsFor(Phase phase) {
		if (this.unavailable != null) {
			throw this.unavailable;
		}
		return phase.tools().stream().<ToolCallback>map(FakeLabTools::definition).toList();
	}

	List<String> calls() {
		return this.calls;
	}

	String output(String tool) {
		return this.outputs.get(tool);
	}

	static String fixture(String name) {
		try (InputStream in = FakeLabTools.class.getResourceAsStream("/mcp-outputs/" + name)) {
			if (in == null) {
				throw new IllegalArgumentException("no fixture " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static ToolCallback definition(String name) {
		ToolDefinition definition = ToolDefinition.builder()
			.name(name)
			.description("the " + name + " tool")
			.inputSchema("{\"type\":\"object\"}")
			.build();
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				throw new UnsupportedOperationException("definitions only");
			}

		};
	}

}
