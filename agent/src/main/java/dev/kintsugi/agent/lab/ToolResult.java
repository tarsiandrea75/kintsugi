package dev.kintsugi.agent.lab;

/**
 * One tool call as the step table records it.
 *
 * @param tool the tool name
 * @param args the arguments actually sent, as JSON, after the limit caps; what {@code tool_args} records
 * @param rawOutput the full text mcp-tools returned, or, for a {@code CLIENT_ERROR}, the reason the call failed
 * @param outcome how the call ended
 * @param durationMs the wall-clock time of the call
 */
public record ToolResult(String tool, String args, String rawOutput, ToolOutcome outcome, long durationMs) {
}
