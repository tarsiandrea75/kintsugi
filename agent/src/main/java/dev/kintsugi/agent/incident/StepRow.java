package dev.kintsugi.agent.incident;

import java.time.Instant;

/**
 * One row of {@code incident_steps} as the loop writes it (agent spec, "State tables"). A null field is a null
 * column.
 *
 * @param stepNo the step's number, from 1, without gaps
 * @param kind {@code PLAN}, {@code ACT}, {@code TOOL_CALL}, {@code REPORT}, {@code BUDGET} or {@code DOSSIER}
 * @param phase the phase the step ran in; {@code DOSSIER} for the dossier step, before the first phase
 * @param model the model that answered, or that requested the tool call
 * @param tokens the tokens of a model answer
 * @param toolName the tool a {@code TOOL_CALL} asked for
 * @param toolArgs the arguments actually sent, or the model's arguments for a refused call, as JSON
 * @param toolOutput the full output of a call that ran
 * @param toolOutcome how a tool call ended, its refusals included
 * @param summary what the model saw of a tool call; for a plan or a report, its validation result
 * @param modelOutput the model's raw answer
 * @param latencyMs the model call's or the tool call's wall-clock time
 * @param createdAt when the step ended
 */
public record StepRow(int stepNo, String kind, String phase, String model, Integer tokens, String toolName,
		String toolArgs, String toolOutput, String toolOutcome, String summary, String modelOutput, Integer latencyMs,
		Instant createdAt) {
}
