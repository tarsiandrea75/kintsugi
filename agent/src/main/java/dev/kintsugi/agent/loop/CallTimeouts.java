package dev.kintsugi.agent.loop;

import java.time.Duration;

/**
 * How long one model call may take before the run ends {@code TIMED_OUT}, per task (agent spec, "Budgets"; ADR-010,
 * "Amended in T5c4"). A call also never waits past the incident's time left.
 *
 * @param act an {@code ACT} answer
 * @param report a {@code REPORT} answer, which reads the longest prompt and writes the longest answer
 */
public record CallTimeouts(Duration act, Duration report) {

	public CallTimeouts {
		if (act == null || report == null || act.isNegative() || act.isZero() || report.isNegative()
				|| report.isZero()) {
			throw new IllegalArgumentException(
					"kintsugi.agent.model.call-timeout needs a positive act and report, got act " + act + ", report "
							+ report);
		}
	}

	/** The deadline of one call of {@code task}. */
	public Duration of(ModelTask task) {
		return task == ModelTask.REPORT ? this.report : this.act;
	}

	/** The longest deadline of any call. */
	public Duration longest() {
		return this.act.compareTo(this.report) >= 0 ? this.act : this.report;
	}

}
