package dev.kintsugi.agent.lab;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LabTools} over the one MCP connection to mcp-tools.
 *
 * <p>
 * Definitions come from Spring AI's MCP {@link ToolCallback}s. Calls go to the {@link McpSyncClient} directly: the
 * MCP {@code ToolCallback} turns a tool error ({@code isError}) into an exception and returns the content list
 * re-serialized, so the {@code INVALID_PARAMETER} or {@code UPSTREAM_ERROR} result, which is evidence, would be lost;
 * the client returns the text exactly as mcp-tools wrote it.
 */
public class McpLabTools implements LabTools {

	/** The {@code limit} each tool may send at most (agent spec, "Tool calls run small and recorded"). */
	static final Map<String, Integer> LIMIT_CAPS = Map.of("search_logs", 20, "recent_events", 20, "search_runbooks", 3);

	/** The reason recorded when the model's arguments are not a JSON object. */
	static final String NOT_AN_OBJECT = "the arguments are not a JSON object";

	/** The text the MCP Java SDK's input-schema validation answers with: "Tool (name) input validation failed: ...". */
	static final String INPUT_VALIDATION_FAILED = "input validation failed:";

	private static final String TRUNCATED_SUFFIX = "\n[truncated]";

	private static final Logger log = LoggerFactory.getLogger(McpLabTools.class);

	private static final JsonMapper JSON = JsonMapper.shared();

	private final McpSyncClient client;

	private final ToolCallbackProvider definitions;

	public McpLabTools(McpSyncClient client, ToolCallbackProvider definitions) {
		this.client = client;
		this.definitions = definitions;
	}

	@Override
	public ToolResult call(String tool, String jsonArgs) {
		if (!Phase.isLabTool(tool)) {
			throw new IllegalArgumentException("not an mcp-tools tool: " + tool);
		}
		ObjectNode args;
		try {
			args = capped(tool, parse(jsonArgs));
		}
		catch (JacksonException | IllegalArgumentException unreadable) {
			// The model wrote arguments that are not a JSON object: nothing is sent, as mcp-tools would refuse them.
			// They are recorded as one JSON string, so the step still shows what the model wrote, and the output is
			// mcp-tools' own rejection envelope, so the summarizer reads its reason like any other rejection.
			return new ToolResult(tool, JSON.writeValueAsString(String.valueOf(jsonArgs)),
					rejection(NOT_AN_OBJECT), ToolOutcome.INVALID_PARAMETER, 0);
		}
		String sent = JSON.writeValueAsString(args);
		long start = System.nanoTime();
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> arguments = JSON.convertValue(args, LinkedHashMap.class);
			CallToolResult result = this.client.callTool(CallToolRequest.builder(tool).arguments(arguments).build());
			String text = text(result);
			if (Boolean.TRUE.equals(result.isError()) && !isJsonObject(text) && text.contains(INPUT_VALIDATION_FAILED)) {
				// The MCP server's input-schema validation rejected the call before mcp-tools' handler ran, in plain
				// text (e.g. "required property 'service' not found"). It is a rejection of the model's arguments, so
				// it is recorded in mcp-tools' envelope and the model learns which argument was wrong. Any other
				// plain-text error is a failure of the server, not of the arguments, and stays a client error.
				return new ToolResult(tool, sent, rejection(text.replaceAll("\\s+", " ").strip()),
						ToolOutcome.INVALID_PARAMETER, elapsedMs(start));
			}
			return new ToolResult(tool, sent, text, outcome(text), elapsedMs(start));
		}
		catch (RuntimeException failed) {
			String reason = "mcp-tools call failed: " + describe(failed);
			log.warn("event=tool_call_failed tool={} reason=\"{}\"", tool, reason);
			return new ToolResult(tool, sent, reason, ToolOutcome.CLIENT_ERROR, elapsedMs(start));
		}
	}

	@Override
	public List<ToolCallback> toolsFor(Phase phase) {
		if (phase.tools().isEmpty()) {
			return List.of();
		}
		ToolCallback[] served;
		try {
			served = this.definitions.getToolCallbacks();
		}
		catch (RuntimeException failed) {
			throw new LabToolsUnavailableException("mcp-tools did not list its tools: " + describe(failed), failed);
		}
		List<ToolCallback> offered = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String name : phase.tools()) {
			ToolCallback match = null;
			for (ToolCallback callback : served) {
				if (callback.getToolDefinition().name().equals(name)) {
					match = callback;
				}
			}
			if (match == null) {
				missing.add(name);
			}
			else {
				offered.add(new DefinitionOnly(match.getToolDefinition()));
			}
		}
		if (!missing.isEmpty()) {
			throw new LabToolsUnavailableException("mcp-tools does not serve " + missing, null);
		}
		return List.copyOf(offered);
	}

	private static ObjectNode parse(String jsonArgs) {
		if (jsonArgs == null || jsonArgs.isBlank()) {
			return JSON.createObjectNode();
		}
		JsonNode node = JSON.readTree(jsonArgs);
		if (!(node instanceof ObjectNode object)) {
			throw new IllegalArgumentException("not a JSON object");
		}
		return object;
	}

	/** An {@code INVALID_PARAMETER} result in the envelope mcp-tools writes, with no items. */
	private static String rejection(String reason) {
		ObjectNode output = JSON.createObjectNode();
		output.put("outcome", "INVALID_PARAMETER");
		output.put("reason", reason);
		output.put("returned", 0);
		output.put("truncated", false);
		output.putArray("items");
		return JSON.writeValueAsString(output);
	}

	/**
	 * Caps {@code limit} at the tool's maximum, or sets it when missing. A value that is not an integer, or below 1, is
	 * left as is: mcp-tools rejects it with {@code INVALID_PARAMETER}, which tells the model what is wrong.
	 */
	static ObjectNode capped(String tool, ObjectNode args) {
		Integer cap = LIMIT_CAPS.get(tool);
		if (cap == null) {
			return args;
		}
		ObjectNode copy = args.deepCopy();
		JsonNode limit = copy.get("limit");
		boolean aboveCap = limit != null && limit.isIntegralNumber()
				&& limit.bigIntegerValue().compareTo(BigInteger.valueOf(cap)) > 0;
		if (limit == null || limit.isNull() || aboveCap) {
			copy.put("limit", cap);
		}
		return copy;
	}

	/** The single text content mcp-tools returns; anything else is unreadable for the agent. */
	private static String text(CallToolResult result) {
		if (result.content().size() != 1 || !(result.content().get(0) instanceof TextContent content)) {
			throw new IllegalStateException("expected one text content, got " + result.content().size() + " items");
		}
		return content.text();
	}

	/**
	 * Whether the text is a JSON object, as mcp-tools' envelope is, once the {@code [truncated]} marker mcp-tools
	 * appends after a cut is removed.
	 */
	private static boolean isJsonObject(String text) {
		try {
			return JSON.readTree(withoutMarker(text)) instanceof ObjectNode;
		}
		catch (JacksonException unreadable) {
			return false;
		}
	}

	private static String withoutMarker(String text) {
		return text.endsWith(TRUNCATED_SUFFIX) ? text.substring(0, text.length() - TRUNCATED_SUFFIX.length()) : text;
	}

	/** mcp-tools' own outcome, from its result; a result without one is unreadable, so a client error. */
	private static ToolOutcome outcome(String text) {
		String outcome;
		try {
			outcome = JSON.readTree(withoutMarker(text)).path("outcome").asString("");
		}
		catch (JacksonException unreadable) {
			throw new IllegalStateException("the result is not JSON");
		}
		return switch (outcome) {
			case "OK" -> ToolOutcome.OK;
			case "INVALID_PARAMETER" -> ToolOutcome.INVALID_PARAMETER;
			case "UPSTREAM_ERROR" -> ToolOutcome.UPSTREAM_ERROR;
			default -> throw new IllegalStateException("the result has no known outcome");
		};
	}

	/** The exception and its root cause, on one line: {@code RuntimeException: ... (cause ConnectException: ...)}. */
	private static String describe(Throwable failed) {
		Throwable root = failed;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String outer = failed.getClass().getSimpleName() + ": " + failed.getMessage();
		String text = root == failed ? outer
				: outer + " (cause " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
		return text.replaceAll("\\s+", " ").strip();
	}

	private static long elapsedMs(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}

	/** A tool definition for the model's request that cannot be executed: the loop calls tools through {@link #call}. */
	private record DefinitionOnly(ToolDefinition definition) implements ToolCallback {

		@Override
		public ToolDefinition getToolDefinition() {
			return this.definition;
		}

		@Override
		public String call(String toolInput) {
			throw new UnsupportedOperationException(
					"tool " + this.definition.name() + " runs only through LabTools.call, after the loop's checks");
		}

	}

}
