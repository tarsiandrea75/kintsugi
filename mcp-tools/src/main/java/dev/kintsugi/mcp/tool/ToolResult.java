package dev.kintsugi.mcp.tool;

import java.util.List;

/**
 * What a tool found, before rendering.
 *
 * @param items the result items, in the order the model should read them
 * @param truncated whether the tool already cut something (lines, points or series) to respect a cap
 * @param count what the call record reports as {@code count}: lines, series, events or the counted total
 */
public record ToolResult(List<?> items, boolean truncated, long count) {

	public static ToolResult of(List<?> items, boolean truncated) {
		return new ToolResult(items, truncated, items.size());
	}

}
