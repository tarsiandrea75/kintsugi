package dev.kintsugi.agent.lab;

/**
 * How a tool call through mcp-tools ended. The first three are mcp-tools' own outcomes, read from its result;
 * {@code CLIENT_ERROR} means the call did not produce a readable result: mcp-tools unreachable, the MCP request
 * timeout, a protocol error or an unreadable answer (agent spec, "Tool calls run small and recorded"). The loop adds
 * its refusals, which never reach this port.
 */
public enum ToolOutcome {

	OK, INVALID_PARAMETER, UPSTREAM_ERROR, CLIENT_ERROR

}
