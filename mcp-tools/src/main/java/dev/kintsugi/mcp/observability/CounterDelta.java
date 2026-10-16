package dev.kintsugi.mcp.observability;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The exact increments of one Prometheus counter series from its raw samples (mcp-tools spec, "Exact traffic counts
 * over a bounded window"). Prometheus' {@code increase()} extrapolates to the window edges and gives non-integers over
 * short windows, so the tool sums the steps between raw samples instead.
 *
 * <p>
 * Rules, oldest sample first:
 * <ul>
 * <li>a sample at the window start is the baseline and is not counted;</li>
 * <li>a series whose first sample is after the window start was born inside it (Micrometer creates a counter at its
 * first request), so its first value counts in full;</li>
 * <li>each later step counts its rise; after a drop, or when the process start time changed between two samples, the
 * process was restarted or recreated and the new value counts from zero;</li>
 * <li>a series that stops (stale) counts up to its last sample.</li>
 * </ul>
 */
final class CounterDelta {

	private CounterDelta() {
	}

	/**
	 * @param samples the raw samples, oldest first
	 * @param windowStart the window start, in epoch seconds (the first point of the range query)
	 * @param restartedBetween true when the process start time changed between two sample times, in epoch seconds
	 */
	static long increments(List<Sample> samples, long windowStart, BiPredicate<Long, Long> restartedBetween) {
		if (samples.isEmpty()) {
			return 0;
		}
		Sample first = samples.get(0);
		double total = first.time() > windowStart ? first.value() : 0;
		Sample previous = first;
		for (Sample sample : samples.subList(1, samples.size())) {
			boolean reset = sample.value() < previous.value() || restartedBetween.test(previous.time(), sample.time());
			total += reset ? sample.value() : sample.value() - previous.value();
			previous = sample;
		}
		return Math.round(total);
	}

	/** One raw sample: epoch seconds and the counter value. */
	record Sample(long time, double value) {
	}

}
