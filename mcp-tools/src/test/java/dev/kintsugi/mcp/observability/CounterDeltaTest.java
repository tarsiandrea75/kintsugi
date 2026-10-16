package dev.kintsugi.mcp.observability;

import java.util.List;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import dev.kintsugi.mcp.observability.CounterDelta.Sample;

import static org.assertj.core.api.Assertions.assertThat;

/** The exact increments of one counter series from its raw samples, with reset handling (mcp-tools spec). */
class CounterDeltaTest {

	private static final long START = 1_000;

	private static final BiPredicate<Long, Long> NO_RESTART = (before, after) -> false;

	@Test
	void aMonotonicSeriesCountsTheIncreaseOverItsBaselineAtTheWindowStart() {
		List<Sample> samples = List.of(new Sample(START, 10), new Sample(START + 5, 12), new Sample(START + 10, 12),
				new Sample(START + 15, 15));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART)).isEqualTo(5);
	}

	@Test
	void afterADropTheNewValueCountsFromZero() {
		// 10 -> 14 is 4; the process is recreated and its counter restarts: 2 counts in full, then 2 -> 5 is 3.
		List<Sample> samples = List.of(new Sample(START, 10), new Sample(START + 5, 14), new Sample(START + 10, 2),
				new Sample(START + 15, 5));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART)).isEqualTo(9);
	}

	@Test
	void aNewProcessStartTimeCountsTheNextValueFromZeroEvenWithoutADrop() {
		// The old process stopped at 3; the new one has already served 5 by its first scrape.
		List<Sample> samples = List.of(new Sample(START, 1), new Sample(START + 5, 3), new Sample(START + 30, 5));
		BiPredicate<Long, Long> restarted = (before, after) -> before == START + 5 && after == START + 30;

		assertThat(CounterDelta.increments(samples, START, restarted)).isEqualTo(2 + 5);
	}

	@Test
	void aSeriesBornInsideTheWindowCountsItsFirstValueInFull() {
		// No sample at the window start: the counter was created by a request after it, so all of its value is new.
		List<Sample> samples = List.of(new Sample(START + 20, 3), new Sample(START + 25, 5));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART)).isEqualTo(5);
	}

	@Test
	void aSeriesWithoutSamplesCountsZero() {
		assertThat(CounterDelta.increments(List.of(), START, NO_RESTART)).isZero();
	}

	@Test
	void aSeriesThatGoesStaleCountsUpToItsLastSample() {
		// The process went away mid-window: the series simply ends.
		List<Sample> samples = List.of(new Sample(START, 7), new Sample(START + 5, 9));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART)).isEqualTo(2);
	}

	@Test
	void aFlatSeriesCountsZero() {
		List<Sample> samples = List.of(new Sample(START, 24), new Sample(START + 5, 24), new Sample(START + 10, 24));

		assertThat(CounterDelta.increments(samples, START, NO_RESTART)).isZero();
	}

}
