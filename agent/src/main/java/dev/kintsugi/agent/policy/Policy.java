package dev.kintsugi.agent.policy;

import java.util.Collection;
import java.util.Map;

/**
 * The deterministic gate every tool call passes before it is made (agent spec, "The policy authorizes every tool call";
 * ADR-027): a static risk table, and a verdict that authorizes a {@link Risk#READ} and refuses a {@link Risk#WRITE}. A
 * tool the table does not name is a write, so a new tool is refused until someone classifies it. The table is explicit,
 * not derived from the phases or from name patterns, so that it fails closed.
 */
public final class Policy {

	/** The step outcome of a call the policy refuses; the call is never made. */
	public static final String REFUSED_POLICY = "REFUSED_POLICY";

	/** The risk of each of the ten mcp-tools tools (ADR-004): every one only reads. */
	public static final Map<String, Risk> RISKS = Map.of("status_summary", Risk.READ, "query_metric", Risk.READ,
			"service_restarts", Risk.READ, "traffic_counts", Risk.READ, "search_logs", Risk.READ, "count_logs",
			Risk.READ, "get_order_trace", Risk.READ, "recent_events", Risk.READ, "event_counts", Risk.READ,
			"search_runbooks", Risk.READ);

	/** The production policy, over {@link #RISKS}. */
	public static final Policy DEFAULT = new Policy(RISKS);

	private final Map<String, Risk> risks;

	/** A policy over {@code risks}; any tool it does not name is a write. */
	public Policy(Map<String, Risk> risks) {
		this.risks = Map.copyOf(risks);
	}

	/** The risk of {@code tool}: its table entry, else {@link Risk#WRITE}, also for a null name. */
	public Risk risk(String tool) {
		return tool == null ? Risk.WRITE : this.risks.getOrDefault(tool, Risk.WRITE);
	}

	/** Whether a call of {@code tool} may be made: only a read is authorized. */
	public boolean authorizes(String tool) {
		return risk(tool) == Risk.READ;
	}

	/**
	 * What this policy did in an incident whose calls that ran were {@code executed}, one tool name per call, and which
	 * recorded {@code refused} {@link #REFUSED_POLICY} steps.
	 */
	public PolicyCounts counts(Collection<String> executed, int refused) {
		int risky = (int) executed.stream().filter(tool -> risk(tool) == Risk.WRITE).count();
		return new PolicyCounts(risky, refused);
	}

}
