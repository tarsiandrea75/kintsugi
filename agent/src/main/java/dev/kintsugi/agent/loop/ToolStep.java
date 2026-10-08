package dev.kintsugi.agent.loop;

/**
 * A recorded tool step of the running incident, as the evidence check reads it.
 *
 * @param stepNo its {@code step_no}, the {@code toolCallId} a report cites
 * @param tool the tool the model asked for
 * @param outcome its {@code tool_outcome}, refusals included
 * @param output its full {@code tool_output}; null for a refused call
 */
record ToolStep(int stepNo, String tool, String outcome, String output) {

	boolean refused() {
		return this.outcome.startsWith("REFUSED_");
	}

}
