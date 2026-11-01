package dev.kintsugi.agent.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.MetricSeries;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceResources;
import dev.kintsugi.agent.dossier.Gathered;
import dev.kintsugi.agent.dossier.Topology;

/**
 * R-RES, the one rule Config 0R adds to Config 0 (ADR-021, "Definitions" and "The rule"): a service is
 * resource-abnormal when one of its resource series at least doubles its previous mean and rises above its previous
 * max, both windows being usable. It reads only ratios within one series, since the units differ between the tracks.
 * <p>
 * A pure function of the dossier's resources and the topology's services order, as {@link RulesDiagnosis} is. A failed
 * resource fact is no evidence. {@link RulesVersion#V2} runs it after Config 0's rules; Config 0 never does.
 */
final class ResourceRule {

	/** The resource rule. */
	static final String RES = "R-RES";

	static final String RESOURCE_EXHAUSTION = "RESOURCE_EXHAUSTION";

	/** Resource-abnormal: an incident mean at least this many times the previous mean (k), and above its max. */
	static final double RATIO = 2.0;

	/** A usable window holds at least this many points with a value... */
	static final int USABLE_VALUES = 2;

	/** ...and at least this share of the points the fuller of the two windows holds. */
	static final double USABLE_SHARE = 0.5;

	private ResourceRule() {
	}

	/**
	 * Every resource-abnormal service of {@code dossier}, as the verdict R-RES would give for it: the largest resource
	 * ratio first, ties in {@code topology}'s services order (leaf first, as Config 0's ties). Each verdict's facts name
	 * its abnormal series, both means, the previous max and the ratio, in the dossier's metric order.
	 */
	static List<RulesVerdict> abnormal(Dossier dossier, Topology topology) {
		List<String> order = topology.leafFirst();
		List<Abnormal> abnormal = new ArrayList<>();
		for (ServiceResources resources : dossier.resources()) {
			double ratio = 0;
			List<String> facts = new ArrayList<>();
			for (MetricSeries metric : resources.metrics()) {
				if (!(metric.series() instanceof Gathered.Ok<ResourceSeries> ok)) {
					continue;
				}
				Double seriesRatio = ratio(ok.value());
				if (seriesRatio == null || !abnormal(ok.value(), seriesRatio)) {
					continue;
				}
				ratio = Math.max(ratio, seriesRatio);
				facts.add(fact(resources.service(), metric.metric(), ok.value(), seriesRatio));
			}
			if (!facts.isEmpty()) {
				abnormal.add(new Abnormal(resources.service(), ratio, facts));
			}
		}
		return abnormal.stream()
			.sorted(Comparator.comparingDouble((Abnormal a) -> -a.ratio()).thenComparingInt(a -> order(order, a.service())))
			.map(a -> new RulesVerdict(a.service(), RESOURCE_EXHAUSTION, RES, a.facts()))
			.toList();
	}

	/** The series' incident mean over its previous mean, when both windows are usable and that mean is above 0. */
	private static Double ratio(ResourceSeries series) {
		int fuller = Math.max(series.previous().points(), series.incident().points());
		if (!usable(series.previous(), fuller) || !usable(series.incident(), fuller)) {
			return null;
		}
		double previous = series.previous().mean();
		return previous > 0 ? series.incident().mean() / previous : null;
	}

	private static boolean usable(LatencyPoints window, int fuller) {
		return window.values() >= USABLE_VALUES && window.values() >= USABLE_SHARE * fuller && window.mean() != null
				&& window.max() != null;
	}

	private static boolean abnormal(ResourceSeries series, double ratio) {
		return ratio >= RATIO && series.incident().mean() > series.previous().max();
	}

	private static String fact(String service, String metric, ResourceSeries series, double ratio) {
		return String.format(Locale.ROOT, "%s %s: incident mean %.4g, %.2fx the previous mean %.4g, above the previous"
				+ " max %.4g", service, metric, series.incident().mean(), ratio, series.previous().mean(),
				series.previous().max());
	}

	private static int order(List<String> order, String service) {
		int index = order.indexOf(service);
		return index < 0 ? Integer.MAX_VALUE : index;
	}

	/** A resource-abnormal service: its resource ratio and the facts of its abnormal series. */
	private record Abnormal(String service, double ratio, List<String> facts) {
	}

}
