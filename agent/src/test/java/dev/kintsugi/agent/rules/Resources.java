package dev.kintsugi.agent.rules;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.MetricSeries;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceLatency;
import dev.kintsugi.agent.dossier.Dossier.ServiceResources;
import dev.kintsugi.agent.dossier.Dossier.UriLatency;
import dev.kintsugi.agent.dossier.Dossier.Window;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.Gathered;
import dev.kintsugi.agent.dossier.RecordedLab;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.lab.ToolOutcome;

/**
 * Synthetic resource facts for the tests of Config 0R (ADR-021): windows, series, a service's metrics, and dossiers
 * that hold them, either bare (no traffic, latency, restarts or logs) or a golden dossier with its resources replaced.
 */
final class Resources {

	static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

	static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private Resources() {
	}

	/** A window of {@code points} points, {@code values} of them with a value. */
	static LatencyPoints window(int points, int values, Double mean, Double max) {
		return new LatencyPoints(points, values, mean, max);
	}

	/** A series over two full windows of 10 points: the previous mean and max, and the incident mean, its own max. */
	static ResourceSeries steady(double previousMean, double previousMax, double incidentMean) {
		return series(window(10, 10, previousMean, previousMax), window(10, 10, incidentMean, incidentMean));
	}

	static ResourceSeries series(LatencyPoints previous, LatencyPoints incident) {
		return new ResourceSeries(false, previous, incident);
	}

	static MetricSeries metric(String metric, ResourceSeries series) {
		return new MetricSeries(metric, new Gathered.Ok<>(series));
	}

	static MetricSeries failed(String metric) {
		return new MetricSeries(metric, new Gathered.Failed<>("query_metric", ToolOutcome.UPSTREAM_ERROR,
				"synthetic failure"));
	}

	static ServiceResources service(String service, MetricSeries... metrics) {
		return new ServiceResources(service, List.of(metrics));
	}

	/** A dossier with these resources and nothing else: no traffic, latency, restart or log line. */
	static Dossier bare(ServiceResources... resources) {
		return bare(List.of(), resources);
	}

	/** A bare dossier with these latency series, as {@link #latency} builds them, and these resources. */
	static Dossier bare(List<ServiceLatency> latency, ServiceResources... resources) {
		Window incident = new Window(10, 0, NOW.minusSeconds(600), NOW);
		Window previous = new Window(20, 10, NOW.minusSeconds(1200), NOW.minusSeconds(600));
		return new Dossier(NOW, incident, previous, new Gathered.Ok<>(List.of()), List.of(),
				new Compared<>(new Gathered.Ok<>(List.of()), new Gathered.Ok<>(List.of())), latency,
				List.of(resources), List.of(), List.of(), List.of());
	}

	/** One URI's latency of {@code service}, flat in each window at the given means, in seconds. */
	static ServiceLatency latency(String service, String uri, double previous, double incident) {
		return new ServiceLatency(service, new Gathered.Ok<>(new LatencySeries(false,
				List.of(new UriLatency(uri, window(10, 10, previous, previous), window(10, 10, incident, incident))))));
	}

	/** {@code dossier} with its resources replaced by {@code resources}. */
	static Dossier with(Dossier dossier, ServiceResources... resources) {
		return new Dossier(dossier.gatheredAt(), dossier.incidentWindow(), dossier.previousWindow(),
				dossier.services(), dossier.states(), dossier.traffic(), dossier.latency(), List.of(resources),
				dossier.logs(), dossier.events(), dossier.calls());
	}

	/** A golden dossier, gathered from the recorded outputs as the dossier tests gather it, over {@code topology}. */
	static Dossier golden(Topology topology, String set, String... fallbacks) {
		return new DossierGatherer(new RecordedLab(set, fallbacks), CLOCK, topology).gather(10);
	}

	/** Every resource series of {@code dossier}, whatever its outcome. */
	static List<Gathered<ResourceSeries>> allSeries(Dossier dossier) {
		List<Gathered<ResourceSeries>> all = new ArrayList<>();
		dossier.resources().forEach(service -> service.metrics().forEach(metric -> all.add(metric.series())));
		return all;
	}

}
