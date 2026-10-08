package dev.kintsugi.mcp.tool;

/** How a tool call ended; written in every result and in the call record (mcp-tools spec). */
public enum Outcome {

	OK, INVALID_PARAMETER, UPSTREAM_ERROR

}
