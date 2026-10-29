package dev.kintsugi.agent.dossier;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.lab.ToolResult;

/**
 * The lab as the dossier sees it: each call is answered with an output recorded in the shape of mcp-tools' results
 * ({@code src/test/resources/dossier/<set>/<key>.json}, from the first set that holds the key), keyed by the tool,
 * its subject (metric, table, source, or service and level) and its window ({@code previous} when the call carries
 * {@code untilMinutesAgo}; none for {@code service_restarts} and {@code query_metric}, whose one call covers what it
 * covers). A log call without a fixture answers an empty window. Every call is kept, in order.
 */
public final class RecordedLab implements LabTools {

	private static final JsonMapper JSON = JsonMapper.shared();

	private final List<String> sets;

	private final Map<String, String> outputs = new HashMap<>();

	private final Map<String, RuntimeException> failures = new HashMap<>();

	private final List<ToolResult> calls = new ArrayList<>();

	/** The lab of {@code set}, then of each of {@code fallbacks} for a key {@code set} does not hold. */
	public RecordedLab(String set, String... fallbacks) {
		this.sets = new ArrayList<>(List.of(set));
		this.sets.addAll(List.of(fallbacks));
	}

	/** Answers the call with {@code key} with {@code output} instead of its fixture. */
	RecordedLab answer(String key, String output) {
		this.outputs.put(key, output);
		return this;
	}

	/** Makes the call with {@code key} throw {@code failure}. */
	RecordedLab fail(String key, RuntimeException failure) {
		this.failures.put(key, failure);
		return this;
	}

	@Override
	public ToolResult call(String tool, String jsonArgs) {
		JsonNode args = JSON.readTree(jsonArgs);
		String key = key(tool, args);
		this.calls.add(new ToolResult(tool, jsonArgs, "", ToolOutcome.OK, 0));
		RuntimeException failure = this.failures.get(key);
		if (failure != null) {
			throw failure;
		}
		String output = this.outputs.computeIfAbsent(key, k -> fixture(tool, args, k));
		if (output.startsWith("mcp-tools call failed:")) {
			return new ToolResult(tool, jsonArgs, output, ToolOutcome.CLIENT_ERROR, 7);
		}
		String outcome = JSON.readTree(output).path("outcome").asString();
		return new ToolResult(tool, jsonArgs, output, ToolOutcome.valueOf(outcome), 7);
	}

	@Override
	public List<ToolCallback> toolsFor(Phase phase) {
		throw new UnsupportedOperationException("the dossier offers no tools to a model");
	}

	/** The calls made, in order: their tool and arguments. */
	List<String> calls() {
		return this.calls.stream().map(call -> call.tool() + " " + call.args()).toList();
	}

	static String key(String tool, JsonNode args) {
		StringBuilder key = new StringBuilder(tool);
		for (String subject : List.of("metric", "table", "source", "service", "level")) {
			if (args.has(subject)) {
				key.append('-').append(args.get(subject).asString());
			}
		}
		if (!tool.equals("service_restarts") && !tool.equals("query_metric")) {
			key.append(args.has("untilMinutesAgo") ? "-previous" : "-incident");
		}
		return key.toString();
	}

	private String fixture(String tool, JsonNode args, String key) {
		for (String set : this.sets) {
			try (InputStream in = RecordedLab.class.getResourceAsStream("/dossier/" + set + "/" + key + ".json")) {
				if (in != null) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
				}
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return switch (tool) {
			case "search_logs" -> "{\"outcome\":\"OK\",\"returned\":0,\"truncated\":false,\"items\":[]}";
			case "count_logs" -> "{\"outcome\":\"OK\",\"returned\":1,\"truncated\":false,\"items\":[{\"service\":\""
					+ args.get("service").asString() + "\",\"count\":0}]}";
			default -> throw new IllegalArgumentException("no fixture " + this.sets + " " + key);
		};
	}

}
