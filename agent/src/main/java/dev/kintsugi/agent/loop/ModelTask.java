package dev.kintsugi.agent.loop;

/** What a model call is for; {@link ModelRouter} picks a route per task (ADR-010, "The model router"). */
public enum ModelTask {

	/** The plan at the start of an evidence phase. */
	PLAN,

	/** An answer within an evidence phase, which may request tool calls. */
	ACT,

	/** The final report. */
	REPORT

}
