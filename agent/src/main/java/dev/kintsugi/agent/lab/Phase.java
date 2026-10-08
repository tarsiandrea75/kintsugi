package dev.kintsugi.agent.lab;

import java.util.List;

/**
 * The loop's phases and the tools each one offers the model (agent spec, "Phases and their tools"; ADR-010). The
 * order of the constants is the order of the run.
 */
public enum Phase {

	TRIAGE(List.of("status_summary", "query_metric", "service_restarts")),

	INVESTIGATE(List.of("search_logs", "count_logs", "recent_events", "get_order_trace")),

	KNOWLEDGE(List.of("search_runbooks")),

	CONCLUDE(List.of());

	private final List<String> tools;

	Phase(List<String> tools) {
		this.tools = tools;
	}

	/** The names of the tools offered in this phase, in a fixed order. */
	public List<String> tools() {
		return this.tools;
	}

	/** Whether {@code tool} is one of the 8 mcp-tools tools, whatever the phase. */
	public static boolean isLabTool(String tool) {
		for (Phase phase : values()) {
			if (phase.tools.contains(tool)) {
				return true;
			}
		}
		return false;
	}

}
