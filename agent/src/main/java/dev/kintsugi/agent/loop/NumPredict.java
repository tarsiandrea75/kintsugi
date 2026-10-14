package dev.kintsugi.agent.loop;

/**
 * The most tokens a model answer may hold, Ollama's {@code num_predict}, per kind of call (agent spec, "Answer caps";
 * ADR-010, "Amended in T5c"). On the laptop's CPU an answer is written at about 3 tokens per second, so a cap is also a
 * bound on the call's time: an answer that runs on is cut and handled by code instead of ending the run at the call's
 * time budget.
 *
 * @param firstAct the first {@code ACT} answer of a phase, which carries the phase's plan as well as its tool calls
 * @param act every later {@code ACT} answer, which only requests tool calls or ends the phase with no text
 * @param report a {@code REPORT} answer, the schema-constrained report
 */
public record NumPredict(int firstAct, int act, int report) {

	public NumPredict {
		if (firstAct < 1 || act < 1 || report < 1) {
			throw new IllegalArgumentException("every num-predict cap must be at least 1 token, got first-act "
					+ firstAct + ", act " + act + ", report " + report);
		}
	}

	/** The cap of one call: the report's, or an {@code ACT} answer's, the larger one when it carries the plan. */
	public int of(ModelTask task, boolean withPlan) {
		if (task == ModelTask.REPORT) {
			return this.report;
		}
		return withPlan ? this.firstAct : this.act;
	}

}
