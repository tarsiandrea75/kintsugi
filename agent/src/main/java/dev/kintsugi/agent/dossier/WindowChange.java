package dev.kintsugi.agent.dossier;

import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;

/**
 * Whether one fact changed between the previous and the incident window, per section (ADR-028, Decision 5). Where
 * Config 0 already decides change, its test is reused with the same constants: latency is R-DOWN's slow test,
 * resources R-RES, status a restart in the incident window. Traffic, states and events use a new count rule. The
 * constants are copies, not references, since the dossier package must not depend on the rules, which read it; a test
 * in the rules package pins each copy to its rule's constant. Logs and header lines have no rule here: their lines are
 * {@link Change#NOT_COMPARED}.
 */
public final class WindowChange {

	/** Latency, R-DOWN's {@code SLOW_RATIO}: an incident mean at least this many times the previous mean... */
	public static final double SLOW_RATIO = 2.0;

	/** ...and at least this many seconds, R-DOWN's {@code SLOW_SECONDS}. */
	public static final double SLOW_SECONDS = 0.5;

	/** Resources, R-RES's {@code RATIO}: an incident mean at least this many times the previous mean, above its max. */
	public static final double RESOURCE_RATIO = 2.0;

	/** R-RES's {@code USABLE_VALUES}: a usable window holds at least this many points with a value... */
	public static final int USABLE_VALUES = 2;

	/** ...and R-RES's {@code USABLE_SHARE}: at least this share of the points the fuller of the two windows holds. */
	public static final double USABLE_SHARE = 0.5;

	/**
	 * Counts: the larger of the two counts is at least this (a new rule, mirroring R-DOWN's {@code REFUSING_ANSWERS}
	 * and {@code SLOW_TIMEOUTS})...
	 */
	public static final long COUNT_MIN = 3;

	/** ...and at least this many times the smaller. */
	public static final long COUNT_RATIO = 2;

	private WindowChange() {
	}

	/**
	 * A latency row: {@link Change#YES} when the incident mean is at least {@link #SLOW_RATIO} times the previous mean
	 * and at least {@link #SLOW_SECONDS}; {@link Change#NOT_COMPARED} when either mean is missing or the previous mean
	 * is not above 0, since R-DOWN computes no ratio then.
	 */
	public static Change latency(LatencyPoints previous, LatencyPoints incident) {
		Double before = previous == null ? null : previous.mean();
		Double now = incident == null ? null : incident.mean();
		if (before == null || before <= 0 || now == null) {
			return Change.NOT_COMPARED;
		}
		return now >= SLOW_RATIO * before && now >= SLOW_SECONDS ? Change.YES : Change.NO;
	}

	/**
	 * A resource series: {@link Change#YES} when both windows are usable, the previous mean is above 0, and the
	 * incident mean is at least {@link #RESOURCE_RATIO} times the previous mean and above the previous max;
	 * {@link Change#NOT_COMPARED} when a window is not usable or the previous mean is not above 0, since R-RES computes
	 * no ratio then.
	 */
	public static Change resource(ResourceSeries series) {
		int fuller = Math.max(series.previous().points(), series.incident().points());
		if (!usable(series.previous(), fuller) || !usable(series.incident(), fuller) || series.previous().mean() <= 0) {
			return Change.NOT_COMPARED;
		}
		double ratio = series.incident().mean() / series.previous().mean();
		return ratio >= RESOURCE_RATIO && series.incident().mean() > series.previous().max() ? Change.YES : Change.NO;
	}

	private static boolean usable(LatencyPoints window, int fuller) {
		return window.values() >= USABLE_VALUES && window.values() >= USABLE_SHARE * fuller && window.mean() != null
				&& window.max() != null;
	}

	/**
	 * A count row, or a total: {@link Change#YES} when the larger of the two counts is at least {@link #COUNT_MIN} and
	 * at least {@link #COUNT_RATIO} times the smaller, so a row that appears, grows, shrinks or disappears counts;
	 * {@link Change#NOT_COMPARED} when a window was not read.
	 */
	public static Change counts(Long previous, Long incident) {
		if (previous == null || incident == null) {
			return Change.NOT_COMPARED;
		}
		long larger = Math.max(previous, incident);
		long smaller = Math.min(previous, incident);
		return larger >= COUNT_MIN && larger >= COUNT_RATIO * smaller ? Change.YES : Change.NO;
	}

	/** A service's status: {@link Change#YES} when it restarted inside the incident window, as the rules' test. */
	public static Change restarts(ServiceStatus status) {
		return status.restartsInWindow().isEmpty() ? Change.NO : Change.YES;
	}

}
