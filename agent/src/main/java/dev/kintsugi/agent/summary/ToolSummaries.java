package dev.kintsugi.agent.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.ToolOutcome;

/**
 * Builds what the model sees of a tool call: a deterministic summary of at most {@value #MAX_CHARS} characters,
 * never the raw output (agent spec, "Summaries built in code"; ADR-010).
 *
 * <p>
 * Every summary starts with the header {@code toolCallId=<step_no> tool=<name> outcome=<outcome> returned=<n>
 * truncated=<bool>}, then one line per fact. Every string copied from the output is written as a JSON string literal
 * ({@code field="..."}): its quotes, backslashes and newlines stay escaped, so text from the lab can never close the
 * quote, start a line of its own or pass for a header or an instruction; it is always a labelled value. The escaping
 * is Jackson's, as in mcp-tools' own result, so the text between the quotes is also a substring of the raw output,
 * and a quote of the summary is a quote of the output. Text is only ever cut at its end, never in the middle.
 */
public final class ToolSummaries {

	public static final int MAX_CHARS = 1500;

	static final String CUT_NOTE = "[summary cut at " + MAX_CHARS + " characters]";

	private static final String TRUNCATED_SUFFIX = "\n[truncated]";

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final Map<String, Function<JsonNode, List<String>>> SUMMARIZERS = Map.of( //
			"status_summary", Summarizers::statusSummary, //
			"query_metric", Summarizers::queryMetric, //
			"service_restarts", Summarizers::serviceRestarts, //
			"search_logs", Summarizers::searchLogs, //
			"count_logs", Summarizers::countLogs, //
			"recent_events", Summarizers::recentEvents, //
			"get_order_trace", Summarizers::orderTrace, //
			"search_runbooks", Summarizers::searchRunbooks);

	private ToolSummaries() {
	}

	/**
	 * @param stepNo the step number of the call, printed as {@code toolCallId}, which the report's evidence cites
	 * @param tool the tool name
	 * @param outcome how the call ended
	 * @param rawOutput the full output recorded for the step
	 */
	public static String summarize(int stepNo, String tool, ToolOutcome outcome, String rawOutput) {
		Function<JsonNode, List<String>> summarizer = SUMMARIZERS.get(tool);
		if (summarizer == null) {
			throw new IllegalArgumentException("no summarizer for tool " + tool);
		}
		JsonNode result = outcome == ToolOutcome.CLIENT_ERROR ? null : parse(rawOutput);
		boolean truncated = rawOutput.endsWith(TRUNCATED_SUFFIX)
				|| (result != null && result.path("truncated").asBoolean(false));
		int returned = result == null ? 0 : result.path("returned").asInt(0);
		String header = "toolCallId=" + stepNo + " tool=" + tool + " outcome=" + outcome + " returned=" + returned
				+ " truncated=" + truncated;
		List<String> lines = new ArrayList<>();
		if (outcome != ToolOutcome.OK) {
			lines.add(failure(outcome));
			String reason = outcome == ToolOutcome.CLIENT_ERROR ? rawOutput
					: result == null ? null : result.path("reason").asString(null);
			if (reason != null) {
				lines.add("reason=" + SummaryText.quote(reason, 300));
			}
		}
		else if (result == null) {
			lines.add("the output could not be read as JSON");
		}
		else {
			lines.addAll(summarizer.apply(result));
		}
		return capped(header, lines);
	}

	private static String failure(ToolOutcome outcome) {
		return switch (outcome) {
			case INVALID_PARAMETER -> "the tool rejected the arguments; no query ran";
			case UPSTREAM_ERROR -> "a data source of the tool failed; the failure itself is evidence";
			case CLIENT_ERROR -> "mcp-tools could not be reached or gave no readable result; no evidence was read";
			case OK -> throw new IllegalArgumentException("not a failure");
		};
	}

	/** The JSON object of a result, without the {@code [truncated]} marker mcp-tools appends after a cut. */
	private static JsonNode parse(String rawOutput) {
		String json = rawOutput.endsWith(TRUNCATED_SUFFIX)
				? rawOutput.substring(0, rawOutput.length() - TRUNCATED_SUFFIX.length()) : rawOutput;
		try {
			JsonNode node = JSON.readTree(json);
			return node != null && node.isObject() ? node : null;
		}
		catch (JacksonException unreadable) {
			return null;
		}
	}

	/** Header and lines, dropping whole lines from the end, with a note, until the summary fits. */
	static String capped(String header, List<String> lines) {
		StringBuilder out = new StringBuilder(header);
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int left = MAX_CHARS - out.length() - 1;
			boolean last = i == lines.size() - 1;
			if (line.length() <= left && (last || line.length() + 1 + CUT_NOTE.length() <= left)) {
				out.append('\n').append(line);
			}
			else {
				out.append('\n').append(CUT_NOTE);
				break;
			}
		}
		return out.toString();
	}

}
