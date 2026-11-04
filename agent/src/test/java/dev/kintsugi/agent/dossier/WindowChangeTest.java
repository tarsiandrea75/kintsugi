package dev.kintsugi.agent.dossier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;

/** Each section's "changed" rule at its boundaries (ADR-028, Decision 5). */
class WindowChangeTest {

	@Test
	void latencyChangedAtTwiceThePreviousMeanAndHalfASecond() {
		assertThat(WindowChange.latency(seconds(0.25), seconds(0.5))).isEqualTo(Change.YES);
		assertThat(WindowChange.latency(seconds(0.3), seconds(0.59))).isEqualTo(Change.NO);
		assertThat(WindowChange.latency(seconds(0.2), seconds(0.49))).isEqualTo(Change.NO);
		// A drop is no slowdown: R-DOWN's test is one-sided.
		assertThat(WindowChange.latency(seconds(2.0), seconds(0.5))).isEqualTo(Change.NO);
		// No ratio without a previous mean above 0, nor without an incident mean.
		assertThat(WindowChange.latency(seconds(0.0), seconds(1.0))).isEqualTo(Change.NOT_COMPARED);
		assertThat(WindowChange.latency(new LatencyPoints(10, 0, null, null), seconds(1.0)))
			.isEqualTo(Change.NOT_COMPARED);
		assertThat(WindowChange.latency(seconds(0.3), new LatencyPoints(10, 0, null, null)))
			.isEqualTo(Change.NOT_COMPARED);
	}

	@Test
	void aResourceChangedAtTwiceThePreviousMeanAndAboveThePreviousMaxBothWindowsUsable() {
		assertThat(WindowChange.resource(series(points(4, 1.0, 1.5), points(4, 2.0, 2.5)))).isEqualTo(Change.YES);
		// Equal to the previous max is not above it.
		assertThat(WindowChange.resource(series(points(4, 1.0, 2.0), points(4, 2.0, 2.2)))).isEqualTo(Change.NO);
		assertThat(WindowChange.resource(series(points(4, 1.0, 1.0), points(4, 1.99, 1.99)))).isEqualTo(Change.NO);
		// Usable: at least 2 values and at least half the fuller window's points.
		assertThat(WindowChange.resource(series(points(4, 1.0, 1.0), new LatencyPoints(4, 1, 5.0, 5.0))))
			.isEqualTo(Change.NOT_COMPARED);
		assertThat(WindowChange.resource(series(points(10, 1.0, 1.0), new LatencyPoints(10, 4, 5.0, 5.0))))
			.isEqualTo(Change.NOT_COMPARED);
		assertThat(WindowChange.resource(series(points(10, 1.0, 1.0), new LatencyPoints(10, 5, 5.0, 5.0))))
			.isEqualTo(Change.YES);
		assertThat(WindowChange.resource(series(points(4, 0.0, 0.0), points(4, 2.0, 2.0))))
			.isEqualTo(Change.NOT_COMPARED);
	}

	@Test
	void aCountChangedWhenTheLargerIsAtLeastThreeAndTwiceTheSmaller() {
		assertThat(WindowChange.counts(0L, 3L)).isEqualTo(Change.YES);
		assertThat(WindowChange.counts(0L, 2L)).isEqualTo(Change.NO);
		assertThat(WindowChange.counts(2L, 4L)).isEqualTo(Change.YES);
		assertThat(WindowChange.counts(2L, 3L)).isEqualTo(Change.NO);
		assertThat(WindowChange.counts(3L, 0L)).isEqualTo(Change.YES);
		assertThat(WindowChange.counts(4L, 6L)).isEqualTo(Change.NO);
		assertThat(WindowChange.counts(0L, 0L)).isEqualTo(Change.NO);
		// A window not read has no count.
		assertThat(WindowChange.counts(null, 6L)).isEqualTo(Change.NOT_COMPARED);
		assertThat(WindowChange.counts(4L, null)).isEqualTo(Change.NOT_COMPARED);
	}

	@Test
	void aStatusChangedWhenTheServiceRestartedInTheIncidentWindow() {
		Instant start = Instant.parse("2026-10-16T09:53:12Z");
		assertThat(WindowChange.restarts(new ServiceStatus("a", List.of(start), List.of(start), Duration.ofMinutes(6))))
			.isEqualTo(Change.YES);
		assertThat(WindowChange.restarts(new ServiceStatus("a", List.of(start), List.of(), Duration.ofHours(2))))
			.isEqualTo(Change.NO);
	}

	private static LatencyPoints seconds(double mean) {
		return new LatencyPoints(10, 10, mean, mean);
	}

	private static LatencyPoints points(int points, double mean, double max) {
		return new LatencyPoints(points, points, mean, max);
	}

	private static ResourceSeries series(LatencyPoints previous, LatencyPoints incident) {
		return new ResourceSeries(false, previous, incident);
	}

}
