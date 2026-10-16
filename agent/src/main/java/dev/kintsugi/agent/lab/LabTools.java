package dev.kintsugi.agent.lab;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

/**
 * The agent's only way into the lab: the mcp-tools tools (agent spec, "Isolation"). The loop checks every call
 * (phase, budgets) before it reaches this port.
 */
public interface LabTools {

	/**
	 * Calls one tool, once. The {@code limit} argument is capped first (20 for {@code search_logs} and
	 * {@code recent_events}, 3 for {@code search_runbooks}, set when missing). Never throws for a failed call: a
	 * transport failure is a {@link ToolOutcome#CLIENT_ERROR} result, since an unavailable source is evidence too.
	 * @param tool one of the 10 mcp-tools tools (a phase's tool or a dossier tool); any other name is a bug of the caller
	 * @param jsonArgs the arguments the model wrote, a JSON object; blank stands for {@code {}}
	 * @throws IllegalArgumentException if {@code tool} is not an mcp-tools tool
	 */
	ToolResult call(String tool, String jsonArgs);

	/**
	 * The definitions of the tools offered in {@code phase}, in the phase's order, for the model's request. They are
	 * definitions only: calling one throws, because every call goes through {@link #call} and its checks. Listed from
	 * mcp-tools on first use and cached once a listing succeeds.
	 * @throws LabToolsUnavailableException if mcp-tools cannot list its tools, or does not serve one of the phase's
	 */
	List<ToolCallback> toolsFor(Phase phase);

}
