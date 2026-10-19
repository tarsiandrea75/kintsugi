package dev.kintsugi.agent.lab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LabTools} over recorded incidents, without the lab (agent spec, "Replay of recorded tool outputs").
 *
 * <p>
 * A recording is the incident document {@code GET /incidents/{id}?full=true} returns: every top-level
 * {@code TOOL_CALL} step that reached mcp-tools holds its sent arguments and full output, and the {@code DOSSIER}
 * step holds the dossier's calls as one JSON array. A call is answered by exact match on its tool and the canonical
 * JSON (sorted keys) of its arguments after the same caps {@link McpLabTools} applies; a call no recording holds is a
 * {@link ToolOutcome#CLIENT_ERROR} and is counted, never guessed. Only mcp-tools' own outcomes are replayed: a refused
 * step or a recorded {@code CLIENT_ERROR} is the loop's or the transport's, not the lab's evidence.
 *
 * <p>
 * Not thread-safe: one instance serves one sample, so its counters are that sample's coverage.
 */
public class ReplayLabTools implements LabTools {

	/** The text an unrecorded call is answered with, followed by the tool and its canonical arguments. */
	static final String NOT_RECORDED = "not recorded in this replay: ";

	private static final JsonMapper JSON = JsonMapper.shared();

	private final Map<String, Recorded> recordings = new HashMap<>();

	private final Map<String, ToolDefinition> definitions = new HashMap<>();

	private int conflicts;

	private int recordedCalls;

	private int unrecordedCalls;

	/**
	 * @param toolsList the recorded tool listing, {@code {"tools": [{"name", "description", "inputSchema"}, ...]}}
	 * @param incidents the recorded incidents; when two hold the same call with different outputs, the first wins
	 */
	public ReplayLabTools(JsonNode toolsList, List<JsonNode> incidents) {
		for (JsonNode tool : toolsList.path("tools")) {
			String name = tool.path("name").asString();
			this.definitions.putIfAbsent(name, ToolDefinition.builder()
				.name(name)
				.description(tool.path("description").asString(""))
				.inputSchema(JSON.writeValueAsString(tool.path("inputSchema")))
				.build());
		}
		for (JsonNode incident : incidents) {
			for (JsonNode step : incident.path("steps")) {
				String kind = step.path("kind").asString("");
				if (kind.equals("TOOL_CALL")) {
					record(step.path("toolName").asString(""), step.get("toolArgs"), step.get("toolOutcome"),
							step.get("toolOutput"));
				}
				else if (kind.equals("DOSSIER")) {
					for (JsonNode call : dossierCalls(step.get("toolOutput"))) {
						record(call.path("tool").asString(""), call.get("args"), call.get("outcome"), call.get("output"));
					}
				}
			}
		}
	}

	/** Reads the tool listing and the incidents from files, as {@code verify-agent.sh} saved them. */
	public static ReplayLabTools fromFiles(Path toolsList, List<Path> incidents) {
		List<JsonNode> parsed = new ArrayList<>();
		for (Path incident : incidents) {
			parsed.add(JSON.readTree(incident.toFile()));
		}
		return new ReplayLabTools(JSON.readTree(toolsList.toFile()), parsed);
	}

	@Override
	public ToolResult call(String tool, String jsonArgs) {
		if (!Phase.isLabTool(tool)) {
			throw new IllegalArgumentException("not an mcp-tools tool: " + tool);
		}
		ObjectNode args;
		try {
			args = McpLabTools.sentArgs(tool, jsonArgs);
		}
		catch (JacksonException | IllegalArgumentException unreadable) {
			return McpLabTools.notAnObject(tool, jsonArgs);
		}
		String sent = JSON.writeValueAsString(args);
		String key = key(tool, args);
		Recorded recorded = this.recordings.get(key);
		if (recorded == null) {
			this.unrecordedCalls++;
			return new ToolResult(tool, sent, NOT_RECORDED + key, ToolOutcome.CLIENT_ERROR, 0);
		}
		this.recordedCalls++;
		return new ToolResult(tool, sent, recorded.output(), recorded.outcome(), 0);
	}

	@Override
	public List<ToolCallback> toolsFor(Phase phase) {
		List<ToolCallback> offered = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String name : phase.tools()) {
			ToolDefinition definition = this.definitions.get(name);
			if (definition == null) {
				missing.add(name);
			}
			else {
				offered.add(new McpLabTools.DefinitionOnly(definition));
			}
		}
		if (!missing.isEmpty()) {
			throw new LabToolsUnavailableException("the recorded tool listing does not hold " + missing, null);
		}
		return List.copyOf(offered);
	}

	/** The calls answered from a recording. */
	public int recordedCalls() {
		return this.recordedCalls;
	}

	/** The calls no recording holds, answered as {@code CLIENT_ERROR}. */
	public int unrecordedCalls() {
		return this.unrecordedCalls;
	}

	/** The share of calls answered from a recording, 1 when no call was made. */
	public double coverage() {
		int calls = this.recordedCalls + this.unrecordedCalls;
		return calls == 0 ? 1.0 : (double) this.recordedCalls / calls;
	}

	/** The recorded calls dropped because an earlier recording holds the same call with another output or outcome. */
	public int conflicts() {
		return this.conflicts;
	}

	private void record(String tool, JsonNode args, JsonNode outcome, JsonNode output) {
		ToolOutcome replayed = replayable(outcome);
		if (!Phase.isLabTool(tool) || !(args instanceof ObjectNode object) || output == null || !output.isString()
				|| replayed == null) {
			return;
		}
		Recorded recorded = new Recorded(output.asString(), replayed);
		Recorded first = this.recordings.putIfAbsent(key(tool, object), recorded);
		if (first != null && !first.equals(recorded)) {
			this.conflicts++;
		}
	}

	/** mcp-tools' own outcome, or null for a refusal, a client error or anything else that is not the lab's answer. */
	private static ToolOutcome replayable(JsonNode outcome) {
		if (outcome == null) {
			return null;
		}
		return switch (outcome.asString("")) {
			case "OK" -> ToolOutcome.OK;
			case "INVALID_PARAMETER" -> ToolOutcome.INVALID_PARAMETER;
			case "UPSTREAM_ERROR" -> ToolOutcome.UPSTREAM_ERROR;
			default -> null;
		};
	}

	/** The dossier step's calls: a JSON array, stored as text in {@code tool_output}. */
	private static JsonNode dossierCalls(JsonNode output) {
		if (output == null || output.isNull()) {
			return JSON.createArrayNode();
		}
		JsonNode calls = output.isString() ? JSON.readTree(output.asString()) : output;
		return calls instanceof ArrayNode ? calls : JSON.createArrayNode();
	}

	/** The tool and its arguments with every object's keys sorted, so the order the model wrote them does not count. */
	static String key(String tool, ObjectNode args) {
		return tool + " " + JSON.writeValueAsString(sorted(args));
	}

	private static JsonNode sorted(JsonNode node) {
		if (node instanceof ObjectNode object) {
			Map<String, JsonNode> fields = new TreeMap<>();
			for (Map.Entry<String, JsonNode> field : object.properties()) {
				fields.put(field.getKey(), sorted(field.getValue()));
			}
			ObjectNode copy = JSON.createObjectNode();
			fields.forEach(copy::set);
			return copy;
		}
		if (node instanceof ArrayNode array) {
			ArrayNode copy = JSON.createArrayNode();
			array.forEach(item -> copy.add(sorted(item)));
			return copy;
		}
		return node;
	}

	private record Recorded(String output, ToolOutcome outcome) {
	}

}
