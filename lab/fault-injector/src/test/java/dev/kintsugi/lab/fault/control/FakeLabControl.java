package dev.kintsugi.lab.fault.control;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * In-memory {@link LabControl} for tests: records every call as {@code "activate F1"} or {@code "restore F1"}, and fails
 * an activation or a restore on demand. {@link #holdNextRestore} parks one restore call mid-flight, so a test can
 * interleave two restores deterministically. Real Docker and gateway-mock are exercised by the verify scripts (T5).
 */
public class FakeLabControl implements LabControl {

	private final List<String> calls = new CopyOnWriteArrayList<>();

	private volatile boolean failActivate;

	private volatile boolean failRestore;

	private final AtomicReference<HeldRestore> held = new AtomicReference<>();

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
		HeldRestore hold = held.getAndSet(null);
		if (hold != null) {
			hold.park();
			if (hold.fail) {
				throw new LabControlException("fake held restore failure for " + fault.targetService());
			}
			return;
		}
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

	/**
	 * The next restore call stops inside the lab step until {@link HeldRestore#release()}, then fails when {@code fail}
	 * is true. Restore calls after it are not held.
	 */
	public HeldRestore holdNextRestore(boolean fail) {
		HeldRestore hold = new HeldRestore(fail);
		held.set(hold);
		return hold;
	}

	/** Succeeds again and forgets every call. */
	public void reset() {
		calls.clear();
		held.set(null);
		failActivate = false;
		failRestore = false;
	}

	/** One restore call parked inside the lab step. */
	public static final class HeldRestore {

		private final boolean fail;

		private final CountDownLatch entered = new CountDownLatch(1);

		private final CountDownLatch released = new CountDownLatch(1);

		private HeldRestore(boolean fail) {
			this.fail = fail;
		}

		/** Waits until the held call is inside the lab step. */
		public void awaitEntered() throws InterruptedException {
			if (!entered.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("the held restore was never called");
			}
		}

		/** Lets the held call finish. */
		public void release() {
			released.countDown();
		}

		private void park() {
			entered.countDown();
			try {
				if (!released.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("the held restore was never released");
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(interrupted);
			}
		}

	}

}
