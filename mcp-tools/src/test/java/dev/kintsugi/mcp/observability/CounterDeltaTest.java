package dev.kintsugi.mcp.observability;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import dev.kintsugi.mcp.observability.CounterDelta.Sample;

import static org.assertj.core.api.Assertions.assertThat;

/** The exact increments of one counter series from its raw samples, with reset handling (mcp-tools spec). */
class CounterDeltaTest {

	private static final long START = 1_000;

	private static final BiPredicate<Long, Long> NO_RESTART = (before, after) -> false;

	/** The series was created inside the window: its process started after the window start, or was seen without it. */
	private static final LongPredicate BORN_INSIDE = firstTime -> true;

	/** Nothing tells that the series was created inside the window, so its first in-window sample is the baseline. */
	private static final LongPredicate MAYBE_OLDER = firstTime -> false;

	@Test
	void aMonotonicSeriesCountsTheIncreaseOverItsBaselineAtTheWindowStart() {
		List<Sample> samples = List.of(new Sample(START, 10), new Sample(START + 5, 12), new Sample(START + 10, 12),
				new Sample(START + 15, 15));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, MAYBE_OLDER)).isEqualTo(5);
	}

	@Test
	void afterADropTheNewValueCountsFromZero() {
		// 10 -> 14 is 4; the process is recreated and its counter restarts: 2 counts in full, then 2 -> 5 is 3.
		List<Sample> samples = List.of(new Sample(START, 10), new Sample(START + 5, 14), new Sample(START + 10, 2),
				new Sample(START + 15, 5));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, MAYBE_OLDER)).isEqualTo(9);
	}

	@Test
	void aNewProcessStartTimeCountsTheNextValueFromZeroEvenWithoutADrop() {
		// The old process stopped at 3; the new one has already served 5 by its first scrape.
		List<Sample> samples = List.of(new Sample(START, 1), new Sample(START + 5, 3), new Sample(START + 30, 5));
		BiPredicate<Long, Long> restarted = (before, after) -> before == START + 5 && after == START + 30;

		assertThat(CounterDelta.increments(samples, START, restarted, MAYBE_OLDER)).isEqualTo(2 + 5);
	}

	@Test
	void aSeriesBornInsideTheWindowCountsItsFirstValueInFull() {
		// No sample at the window start: the counter was created by a request after it, so all of its value is new.
		List<Sample> samples = List.of(new Sample(START + 20, 3), new Sample(START + 25, 5));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, BORN_INSIDE)).isEqualTo(5);
	}

	@Test
	void aLongLivedSeriesWithoutAWindowStartSampleCountsOnlyTheRisesAfterItsFirstSample() {
		// R3-001: the scrape at the window start failed, so the series' lifetime value 100 is not new.
		List<Sample> samples = List.of(new Sample(START + 10, 100), new Sample(START + 15, 102),
				new Sample(START + 20, 105));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, MAYBE_OLDER)).isEqualTo(5);
	}

	@Test
	void aFirstSampleAtTheWindowStartIsTheBaselineEvenForASeriesBornInside() {
		List<Sample> samples = List.of(new Sample(START, 3), new Sample(START + 5, 5));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, BORN_INSIDE)).isEqualTo(2);
	}

	@Test
	void aSeriesWithoutSamplesCountsZero() {
		assertThat(CounterDelta.increments(List.of(), START, NO_RESTART, MAYBE_OLDER)).isZero();
	}

	@Test
	void aSeriesThatGoesStaleCountsUpToItsLastSample() {
		// The process went away mid-window: the series simply ends.
		List<Sample> samples = List.of(new Sample(START, 7), new Sample(START + 5, 9));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, MAYBE_OLDER)).isEqualTo(2);
	}

	@Test
	void aFlatSeriesCountsZero() {
		List<Sample> samples = List.of(new Sample(START, 24), new Sample(START + 5, 24), new Sample(START + 10, 24));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART, MAYBE_OLDER)).isZero();
	}

}
