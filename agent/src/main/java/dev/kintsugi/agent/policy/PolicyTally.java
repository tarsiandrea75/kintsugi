package dev.kintsugi.agent.policy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the policy did to one gathering's calls, recorded as they are made (ADR-027): the tool of every call that ran,
 * and the number of calls refused. The gatherer fills it while the loop waits, so it is safe to read from another
 * thread, and it still holds the calls of a gathering that failed or that the loop stopped waiting for. A refusal is
 * kept here rather than as a {@code ToolOutcome}, the lab port's outcomes, since a refused call never reaches the lab.
 * The loop counts from a {@link #snapshot()} taken when it stops waiting, since an abandoned gathering goes on recording.
 */
public final class PolicyTally {

	private final List<String> executed = new CopyOnWriteArrayList<>();

	private final AtomicInteger refused = new AtomicInteger();

	/** Records that a call of {@code tool} is about to run, once the policy has authorized it. */
	public synchronized void recordCall(String tool) {
		this.executed.add(tool);
	}

	/** Records that the policy refused a call, which is not made. */
	public synchronized void recordRefusal() {
		this.refused.incrementAndGet();
	}

	/** The tools of the calls that ran, one per call, in the order made. */
	public List<String> executed() {
		return List.copyOf(this.executed);
	}

	/** The number of calls the policy refused. */
	public int refused() {
		return this.refused.get();
	}

	/** This tally's counts, under {@code policy}'s risk table. */
	public PolicyCounts counts(Policy policy) {
		return policy.counts(executed(), refused());
	}

	/** What this tally holds now, both counts at the same moment; calls recorded later do not change it. */
	public synchronized Snapshot snapshot() {
		return new Snapshot(executed(), refused());
	}

	/** A tally's calls that ran and its refusals at one moment. */
	public record Snapshot(List<String> executed, int refused) {

		/** No call made and none refused, as in a run that gathers no dossier. */
		public static final Snapshot NONE = new Snapshot(List.of(), 0);

		public Snapshot {
			executed = List.copyOf(executed);
		}

	}

}
