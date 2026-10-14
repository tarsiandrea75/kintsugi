package dev.kintsugi.agent.loop;

/** What a model call is for; {@link ModelRouter} picks a route per task (ADR-010, "The model router"). */
public enum ModelTask {

	/**
	 * An answer within an evidence phase, which may request tool calls; the first one of each phase also carries the
	 * phase's plan. The run's warm-up goes to this task's route too, since its first real call is one.
	 */
	ACT,

	/** The final report. */
	REPORT

}
