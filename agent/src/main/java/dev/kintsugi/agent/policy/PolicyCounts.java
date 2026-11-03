package dev.kintsugi.agent.policy;

/**
 * What the policy did in one incident (ADR-027, Decision 3).
 *
 * @param riskyExecuted the calls that ran although their risk is {@link Risk#WRITE}: 0 by construction, since the gate
 * refuses every write, and counted from what ran so that a run shows it rather than assumes it
 * @param refused the steps refused {@code REFUSED_POLICY}
 */
public record PolicyCounts(int riskyExecuted, int refused) {

	/** No write ran and nothing was refused. */
	public static final PolicyCounts NONE = new PolicyCounts(0, 0);

}
