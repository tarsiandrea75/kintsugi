package dev.kintsugi.mcp.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Runs one tool call: turns its outcome into the MCP result the model reads, caps that result at 8 KB, and writes
 * the one INFO line that records the call (mcp-tools spec, "Every tool call is recorded").
 *
 * <p>
 * The result is a JSON object in a single text content: {@code outcome}, {@code reason} (errors only),
 * {@code returned}, {@code truncated} and {@code items}. When it is longer than 8 KB, items are dropped from the end
 * until it fits, {@code truncated} becomes true and the text ends with {@code [truncated]}. Errors set
 * {@code isError}, so an MCP client sees them as tool errors.
 */
@Component
public class ToolCallRunner {

	public static final int MAX_OUTPUT_BYTES = 8192;

	public static final String TRUNCATED_MARKER = "[truncated]";

	private static final String MARKER_SUFFIX = "\n" + TRUNCATED_MARKER;

	private static final int MAX_LOGGED_VALUE_LENGTH = 200;

	private static final Logger log = LoggerFactory.getLogger(ToolCallRunner.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * @param tool the tool name
	 * @param params the parameters as received, in a stable order; {@code null} values are left out of the record
	 * @param call the validation and the query; throws {@link InvalidParameterException} or {@link UpstreamException}
	 */
	public CallToolResult run(String tool, Map<String, Object> params, Supplier<ToolResult> call) {
		long start = System.nanoTime();
		Outcome outcome;
		String reason = null;
		ToolResult result = new ToolResult(List.of(), false, 0);
		try {
			result = call.get();
			outcome = Outcome.OK;
		}
		catch (InvalidParameterException ex) {
			outcome = Outcome.INVALID_PARAMETER;
			reason = ex.getMessage();
		}
		catch (UpstreamException ex) {
			outcome = Outcome.UPSTREAM_ERROR;
			reason = ex.getMessage();
		}
		catch (RuntimeException ex) {
			// A bug or an answer the parser did not expect: still one record, and a short reason for the model.
			log.warn("Unexpected failure in tool {}", tool, ex);
			outcome = Outcome.UPSTREAM_ERROR;
			reason = "unexpected failure: " + ex.getClass().getSimpleName();
		}
		String text = render(outcome, reason, result);
		long durationMs = (System.nanoTime() - start) / 1_000_000;
		log.info("tool={} params={} outcome={} count={} duration_ms={}", tool, format(params), outcome, result.count(),
				durationMs);
		return CallToolResult.builder().addTextContent(text).isError(outcome != Outcome.OK).build();
	}

	static String render(Outcome outcome, String reason, ToolResult result) {
		List<Object> items = new ArrayList<>(result.items());
		boolean cut = false;
		while (true) {
			String json = JSON.writeValueAsString(body(outcome, reason, items, result.truncated() || cut));
			int bytes = json.getBytes(StandardCharsets.UTF_8).length;
			if (!cut && bytes <= MAX_OUTPUT_BYTES) {
				return json;
			}
			if (cut && bytes + MARKER_SUFFIX.length() <= MAX_OUTPUT_BYTES) {
				return json + MARKER_SUFFIX;
			}
			if (items.isEmpty()) {
				throw new IllegalStateException("A result without items exceeds " + MAX_OUTPUT_BYTES + " bytes");
			}
			items.remove(items.size() - 1);
			cut = true;
		}
	}

	private static Map<String, Object> body(Outcome outcome, String reason, List<Object> items, boolean truncated) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("outcome", outcome);
		if (reason != null) {
			body.put("reason", reason);
		}
		body.put("returned", items.size());
		body.put("truncated", truncated);
		body.put("items", items);
		return body;
	}

	/** {@code {name=value name="text with spaces"}}, one line, values cut at 200 characters. */
	static String format(Map<String, Object> params) {
		StringBuilder out = new StringBuilder("{");
		params.forEach((name, value) -> {
			if (value == null) {
				return;
			}
			if (out.length() > 1) {
				out.append(' ');
			}
			out.append(name).append('=').append(formatValue(value));
		});
		return out.append('}').toString();
	}

	private static String formatValue(Object value) {
		if (value instanceof Number) {
			return value.toString();
		}
		String text = value.toString();
		if (text.length() > MAX_LOGGED_VALUE_LENGTH) {
			text = text.substring(0, MAX_LOGGED_VALUE_LENGTH) + "...";
		}
		// The record stays one line whatever the model sent: control characters become '?'.
		StringBuilder clean = new StringBuilder(text.length());
		text.chars().forEach(c -> clean.append(Character.isISOControl(c) ? '?' : (char) c));
		String safe = clean.toString();
		boolean plain = !safe.isEmpty() && safe.chars().noneMatch(c -> c == ' ' || c == '"' || c == '=' || c == '\\');
		return plain ? safe : "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
