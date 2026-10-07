package dev.kintsugi.lab.fault.control;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * In-memory {@link LabControl} for tests: records every call as {@code "activate F1"} or {@code "restore F1"}, and fails
 * an activation or a restore on demand. Real Docker and gateway-mock are exercised by the verify scripts (T4, T5).
 */
public class FakeLabControl implements LabControl {

	private final List<String> calls = new CopyOnWriteArrayList<>();

	private volatile boolean failActivate;

	private volatile boolean failRestore;

	@Override
	public void activate(Fault fault) {
		calls.add("activate " + fault.id());
		if (failActivate) {
			throw new LabControlException("fake activation failure for " + fault.targetService());
		}
	}

	@Override
	public void restore(Fault fault) {
		calls.add("restore " + fault.id());
		if (failRestore) {
			throw new LabControlException("fake restore failure for " + fault.targetService());
		}
	}

	public List<String> calls() {
		return List.copyOf(calls);
	}

	public void failActivate(boolean fail) {
		this.failActivate = fail;
	}

	public void failRestore(boolean fail) {
		this.failRestore = fail;
	}

	/** Succeeds again and forgets every call. */
	public void reset() {
		calls.clear();
		failActivate = false;
		failRestore = false;
	}

}
