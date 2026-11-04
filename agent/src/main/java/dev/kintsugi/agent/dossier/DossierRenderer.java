package dev.kintsugi.agent.dossier;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.Topology.Edge;
import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.EventCounts;
import dev.kintsugi.agent.dossier.Dossier.EventGroup;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.LogLines;
import dev.kintsugi.agent.dossier.Dossier.LogSignature;
import dev.kintsugi.agent.dossier.Dossier.MetricSeries;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceResources;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceLatency;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.SourceEvents;
import dev.kintsugi.agent.dossier.Dossier.StatusCount;
import dev.kintsugi.agent.dossier.Dossier.TableStates;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.dossier.Dossier.UriLatency;

/**
 * Writes a {@link Dossier} as the compact Markdown the model reads (agent spec, "Evidence dossier rendering";
 * ADR-011). It writes facts only: neutral headings and column labels, numbers, names, times and quoted text, with no
 * word that judges a value. Every section and every fixed row is written for every dossier, in a fixed order, whether
 * its values are zero, empty or missing; a failed call is one fact line in its section. The same dossier always gives
 * the same text: rows are sorted by count then label, numbers are plain integers, times are ISO-8601 UTC, and nothing
 * reads the default locale or time zone. The traffic edges are the {@link Topology}'s, an edge without a caller
 * written without its caller's side, since its caller is outside the system. {@link ServiceSheets} writes the same
 * facts per service and shares the helpers here, so both state a fact in the same words.
 */
public final class DossierRenderer {

	/** The signatures written per service and level at most; the others are counted in one line. */
	static final int SIGNATURES = 3;

	/**
	 * The signatures written in the whole log section at most. They are handed out in rounds, one per level with
	 * signatures left, in the section's order, so every level with lines shows its most frequent signature first.
	 */
	static final int SIGNATURES_IN_SECTION = 8;

	/** The rows written per variable table (states, events, each side of an edge, other traffic rows). */
	static final int ROWS = 5;

	/** The start times in the window written per service, newest first. */
	static final int START_TIMES = 3;

	/**
	 * The rows written per service in the latency table: the lab's services answer on one or two uris besides
	 * {@code /actuator/prometheus}, so 3 keeps them all and bounds the table at 12 rows.
	 */
	static final int LATENCY_ROWS = 3;

	/** The decimals of a latency in seconds: milliseconds. */
	static final int LATENCY_DECIMALS = 3;

	/** The characters of a log message kept in its literal. */
	static final int MESSAGE_CHARS = 300;

	/** The characters of a failure reason kept in its literal. */
	static final int REASON_CHARS = 200;

	/**
	 * The characters of a failed resource call's reason kept in its literal: short, since a gathering makes three such
	 * calls per service and they tend to fail alike, as on a replay that predates them (ADR-021); a service's failures
	 * written the same are one line.
	 */
	static final int RESOURCE_REASON_CHARS = 48;

	/** The significant digits of a resource value, whose unit and size depend on the metric and the source. */
	static final int RESOURCE_DIGITS = 4;

	/** The magnitude from which a resource value is written in scientific notation, so a cell stays short. */
	private static final BigDecimal PLAIN_BELOW = BigDecimal.TEN.pow(12);

	/** The characters of a value kept in a table cell. */
	static final int VALUE_CHARS = 40;

	static final String NO_VALUE = "-";

	/** Between a latency cell's two values, from the previous window to the incident window. */
	static final String ARROW = "→";

	/** The header's line on the text copied from the lab, the same in the tables and the sheets. */
	static final String UNTRUSTED_TEXT_LINE = "- Text copied from the lab is quoted as untrustedText=\"...\".";

	private static final String NO_ROWS = "no rows";

	private static final JsonMapper JSON = JsonMapper.shared();

	/** Labels first absent, then by text. */
	private static final Comparator<List<String>> LABELS = (a, b) -> {
		for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
			int c = Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(a.get(i), b.get(i));
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(a.size(), b.size());
	};

	/** Incident count first, then previous count, both largest first, then the labels. */
	private static final Comparator<Row> ROW_ORDER = Comparator.comparingLong(Row::incidentOrZero)
		.reversed()
		.thenComparing(Comparator.comparingLong(Row::previousOrZero).reversed())
		.thenComparing(Row::labels, LABELS);

	private DossierRenderer() {
	}

	/**
	 * The text of line {@code D<n>} of a rendered dossier, without {@code D<n> }; empty when it has no such line
	 * (agent spec, "Evidence dossier rendering", evidence-by-reference). Only the renderer starts a line with
	 * {@code D}: headings start with {@code #}, table header and separator rows with {@code |}, and text from the lab
	 * is escaped, so it never starts a line of its own.
	 * @param dossier the dossier as {@link #render} wrote it, or null
	 * @param n the cited line's number, from 1
	 */
	public static Optional<String> line(String dossier, int n) {
		if (dossier == null || n < 1) {
			return Optional.empty();
		}
		String prefix = linePrefix(n);
		return dossier.lines()
			.filter(line -> line.startsWith(prefix))
			.map(line -> line.substring(prefix.length()))
			.findFirst();
	}

	/** The prefix of dossier line {@code n}: {@code D<n> }, the same in the tables and the sheets. */
	static String linePrefix(int n) {
		return "D" + n + " ";
	}

	/** An edge's heading in the traffic section. */
	private static String heading(Edge edge) {
		String route = edge.target() + " " + edge.method() + " " + edge.uri();
		return edge.caller() == null ? Topology.EXTERNAL_CLIENT + " -> " + route + " (server side only)"
				: edge.caller() + " -> " + route;
	}

	/**
	 * The tables as written, and the fact of each numbered line (ADR-028, Decision 3): {@code facts.get(n - 1)} is line
	 * {@code D<n>}'s.
	 */
	public record Indexed(String text, List<LineFact> facts) {

		public Indexed {
			facts = List.copyOf(facts);
		}

		/** The fact of line {@code D<n>}; empty when the tables have no such line. */
		public Optional<LineFact> fact(int n) {
			return n < 1 || n > this.facts.size() ? Optional.empty() : Optional.of(this.facts.get(n - 1));
		}

	}

	/** The dossier as Markdown, with {@code topology}'s traffic edges, ending with a newline. */
	public static String render(Dossier dossier, Topology topology) {
		return indexed(dossier, topology).text();
	}

	/**
	 * The dossier as {@link #render} writes it, with the fact of each numbered line: its owner, as the sheets name it
	 * (a status, latency, resource or log row's service, a traffic row's {@code service} label, a table's or a source's
	 * owner in {@code topology}), its section, and whether it changed by {@link WindowChange}.
	 */
	public static Indexed indexed(Dossier dossier, Topology topology) {
		Out out = new Out();
		header(out, dossier);
		services(out, dossier.services(), topology);
		for (TableStates table : dossier.states()) {
			states(out, table, topology.ownerOfTable(table.table()).orElse(LineFact.HEADER));
		}
		traffic(out, dossier.traffic(), topology);
		latency(out, dossier.latency());
		resources(out, dossier.resources());
		logs(out, dossier.logs());
		events(out, dossier.events(), topology);
		return new Indexed(out.text(), out.facts);
	}

	private static void header(Out out, Dossier dossier) {
		LineFact header = LineFact.notCompared(LineFact.HEADER, Section.HEADER);
		out.heading("# Evidence dossier");
		out.blank();
		windowLines(dossier).forEach(line -> out.line(header, line));
		out.line(header, "- Columns \"previous\" and \"incident\" hold the counts of those windows. \"" + NO_VALUE
				+ "\" means no value.");
		out.line(header, UNTRUSTED_TEXT_LINE);
	}

	/** The header's lines on the two windows and the gathering time, the same in the tables and the sheets. */
	static List<String> windowLines(Dossier dossier) {
		Dossier.Window incident = dossier.incidentWindow();
		Dossier.Window previous = dossier.previousWindow();
		return List.of(
				"- Incident window: the last " + minutes(incident) + " min, " + incident.from() + " to "
						+ incident.to() + ".",
				"- Previous window: the " + minutes(previous) + " min before it, " + previous.from() + " to "
						+ previous.to() + ".",
				"- Gathered at " + dossier.gatheredAt() + ". All times are UTC.");
	}

	/** The status table; when the call failed, one empty row per service of {@code topology}, in gather order. */
	private static void services(Out out, Gathered<List<ServiceStatus>> services, Topology topology) {
		out.section("## Service status (incident window)");
		List<List<String>> rows = new ArrayList<>();
		List<LineFact> facts = new ArrayList<>();
		switch (services) {
			case Gathered.Ok<List<ServiceStatus>> ok -> {
				for (ServiceStatus status : ok.value()) {
					rows.add(List.of(cell(status.service()), String.valueOf(status.restartsInWindow().size()),
							startTimes(status.restartsInWindow()),
							status.uptime() == null ? NO_VALUE : duration(status.uptime())));
					facts.add(new LineFact(status.service(), Section.STATUS, WindowChange.restarts(status)));
				}
			}
			case Gathered.Failed<List<ServiceStatus>> failed -> {
				out.line(LineFact.notCompared(LineFact.HEADER, Section.STATUS), failed(failed, null));
				out.blank();
				for (String service : topology.gatherOrder()) {
					rows.add(List.of(service, NO_VALUE, NO_VALUE, NO_VALUE));
					facts.add(LineFact.notCompared(service, Section.STATUS));
				}
			}
		}
		out.table(List.of("service", "restarts", "start times in window", "uptime at gathering"), rows, facts);
	}

	private static void states(Out out, TableStates table, String owner) {
		String name = switch (table.table()) {
			case "orders" -> "Order states";
			case "payments" -> "Payment states";
			default -> "States";
		};
		out.section("## " + name + " (" + cell(table.table()) + " table)");
		Compared<List<StatusCount>> windows = table.windows();
		failedWindows(out, windows, LineFact.notCompared(owner, Section.STATES));
		Map<List<String>, String> samples = new LinkedHashMap<>();
		if (windows.incident() instanceof Gathered.Ok<List<StatusCount>> ok) {
			ok.value()
				.forEach(s -> samples.put(Arrays.asList(s.status()), s.sampleOrderIds().isEmpty() ? NO_VALUE
						: String.join(", ", s.sampleOrderIds().stream().map(DossierRenderer::cell).toList())));
		}
		List<Row> rows = rows(windows, list -> list.stream()
			.map(s -> Map.entry(Arrays.asList(s.status()), s.count()))
			.toList());
		out.countTable(windows, List.of("status"), List.of("sample order ids, incident"), rows,
				row -> List.of(samples.getOrDefault(row.labels(), NO_VALUE)), counted(owner, Section.STATES));
	}

	/** A count row's fact: {@code owner}'s, in {@code section}, changed by {@link WindowChange#counts}. */
	private static Function<Row, LineFact> counted(String owner, Section section) {
		return row -> new LineFact(owner, section, WindowChange.counts(row.previous(), row.incident()));
	}

	private static void traffic(Out out, Compared<List<TrafficRow>> traffic, Topology topology) {
		List<Edge> edges = topology.edges();
		LineFact header = LineFact.notCompared(LineFact.HEADER, Section.TRAFFIC);
		out.section("## Traffic");
		failedWindows(out, traffic, header);
		out.line(header, "Requests per edge from traffic_counts, which leaves out rows of count 0. \"" + NO_ROWS
				+ "\": none returned for that side in either window.");
		List<String> sideHeaders = List.of("side", "status", "outcome", "exception");
		for (Edge edge : edges) {
			out.section("### " + heading(edge));
			List<Row> rows = new ArrayList<>();
			List<Row> hidden = new ArrayList<>();
			long[] hiddenCounts = new long[2];
			if (edge.caller() != null) {
				List<Row> caller = sideRows(traffic, edge::callerRow, "client");
				rows.addAll(capped(caller));
				hidden.addAll(caller.subList(Math.min(ROWS, caller.size()), caller.size()));
				sum(caller, hiddenCounts);
			}
			List<Row> server = sideRows(traffic, edge::serverRow, "server");
			rows.addAll(capped(server));
			hidden.addAll(server.subList(Math.min(ROWS, server.size()), server.size()));
			sum(server, hiddenCounts);
			// A client row is its caller's, a server row its target's: the service label of the rows on that side.
			Function<Row, LineFact> fact = row -> new LineFact(
					"client".equals(row.labels().get(0)) ? edge.caller() : edge.target(), Section.TRAFFIC,
					WindowChange.counts(row.previous(), row.incident()));
			out.rawCountTable(sideHeaders, List.of(), rows, row -> List.of(), fact);
			if (!hidden.isEmpty()) {
				out.blank();
				out.line(hidden(hidden, fact), notShown(hidden.size(), traffic, hiddenCounts));
			}
		}
		out.section("### Other rows");
		List<Row> others = rows(traffic, list -> list.stream()
			.filter(row -> edges.stream().noneMatch(edge -> edge.callerRow(row) || edge.serverRow(row)))
			.map(row -> Map.entry(Arrays.asList(row.side(), row.service(), row.clientName(), row.method(), row.uri(),
					row.status(), row.outcome(), row.exception()), row.count()))
			.toList());
		out.countTable(traffic, List.of("side", "service", "client", "method", "uri", "status", "outcome", "exception"),
				List.of(), others, row -> List.of(),
				row -> new LineFact(otherRowOwner(row.labels(), topology), Section.TRAFFIC,
						WindowChange.counts(row.previous(), row.incident())));
	}

	/**
	 * The owner of an "Other rows" line, as the sheets name it: a lab service's client or server row on no edge is that
	 * service's; a row of no lab service, and the "no rows" row, the header's.
	 */
	private static String otherRowOwner(List<String> labels, Topology topology) {
		String side = labels.get(0);
		String service = labels.get(1);
		return ("server".equals(side) || "client".equals(side)) && topology.services().contains(service) ? service
				: LineFact.HEADER;
	}

	/** The rows of one side of an edge, by status, outcome and exception; a "no rows" row when there are none. */
	private static List<Row> sideRows(Compared<List<TrafficRow>> traffic,
			Predicate<TrafficRow> side, String label) {
		List<Row> rows = rows(traffic, list -> list.stream()
			.filter(side)
			.map(row -> Map.entry(Arrays.asList(label, row.status(), row.outcome(), row.exception()), row.count()))
			.toList());
		if (rows.isEmpty()) {
			return List.of(new Row(Arrays.asList(label, NO_ROWS, null, null), zeroOrNull(traffic.previous()),
					zeroOrNull(traffic.incident())));
		}
		return rows;
	}

	/**
	 * One row per service and uri, a service's rows sorted by incident mean, then previous mean, largest first, then
	 * by uri; each cell pairs one quantity over both windows, previous first, so that a value is read next to its pair;
	 * every service keeps at least one row, {@code -} when its call failed and {@code no rows} when the tool returned
	 * no series.
	 */
	private static void latency(Out out, List<ServiceLatency> latency) {
		out.section("## Latency (server side)");
		for (ServiceLatency service : latency) {
			if (service.latency() instanceof Gathered.Failed<LatencySeries> failed) {
				out.line(LineFact.notCompared(service.service(), Section.LATENCY),
						failed.tool() + " failed for " + cell(service.service()) + ": " + outcome(failed));
				out.blank();
			}
		}
		out.line(LineFact.notCompared(LineFact.HEADER, Section.LATENCY),
				"Seconds per request by service and uri, from query_metric " + DossierGatherer.LATENCY_METRIC
				+ ": one call per service over both windows, its points split at the incident window's start. \"mean\""
				+ " and \"max\" are of the points with a value; \"points\" counts those with a value and all points in"
				+ " the window. A point has no value when no request ended in its rate window. In each cell the arrow"
				+ " goes from the previous window to the incident window.");
		out.blank();
		List<List<String>> rows = new ArrayList<>();
		List<LineFact> facts = new ArrayList<>();
		List<Map.Entry<String, LineFact>> notes = new ArrayList<>();
		for (ServiceLatency service : latency) {
			String name = cell(service.service());
			LineFact noted = LineFact.notCompared(service.service(), Section.LATENCY);
			switch (service.latency()) {
				case Gathered.Failed<LatencySeries> failed -> {
					rows.add(List.of(name, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE));
					facts.add(noted);
				}
				case Gathered.Ok<LatencySeries> ok -> {
					List<UriLatency> uris = ok.value().uris().stream().sorted(URI_ORDER).toList();
					if (uris.isEmpty()) {
						rows.add(List.of(name, NO_ROWS, NO_VALUE, NO_VALUE, NO_VALUE));
						facts.add(noted);
					}
					for (UriLatency uri : uris.subList(0, Math.min(LATENCY_ROWS, uris.size()))) {
						rows.add(List.of(name, cell(uri.uri()),
								pair(seconds(uri.previous().mean()), seconds(uri.incident().mean())),
								pair(seconds(uri.previous().max()), seconds(uri.incident().max())),
								pair(points(uri.previous()), points(uri.incident()))));
						facts.add(new LineFact(service.service(), Section.LATENCY,
								WindowChange.latency(uri.previous(), uri.incident())));
					}
					if (uris.size() > LATENCY_ROWS) {
						notes.add(Map.entry("not shown for " + name + ": rows=" + (uris.size() - LATENCY_ROWS), noted));
					}
					if (ok.value().truncated()) {
						notes.add(Map.entry("query_metric for " + name + ": truncated=true", noted));
					}
				}
			}
		}
		out.table(List.of("service", "uri", "mean, previous " + ARROW + " incident",
				"max, previous " + ARROW + " incident", "points, previous " + ARROW + " incident"), rows, facts);
		if (!notes.isEmpty()) {
			out.blank();
			notes.forEach(note -> out.line(note.getValue(), note.getKey()));
		}
	}

	/** Incident mean, then previous mean, largest first, a missing mean last, then the uri. */
	static final Comparator<UriLatency> URI_ORDER = Comparator
		.comparing((UriLatency u) -> u.incident().mean(), Comparator.nullsLast(Comparator.<Double>reverseOrder()))
		.thenComparing(u -> u.previous().mean(), Comparator.nullsLast(Comparator.<Double>reverseOrder()))
		.thenComparing(UriLatency::uri, Comparator.nullsFirst(Comparator.<String>naturalOrder()));

	/** Seconds with {@link #LATENCY_DECIMALS} decimals, half up, whatever the locale; {@code -} when absent. */
	static String seconds(Double seconds) {
		return seconds == null ? NO_VALUE
				: BigDecimal.valueOf(seconds).setScale(LATENCY_DECIMALS, RoundingMode.HALF_UP).toPlainString();
	}

	/** One quantity over both windows, previous first: {@code 0.054 → 1.325}. */
	static String pair(String previous, String incident) {
		return previous + " " + ARROW + " " + incident;
	}

	static String points(LatencyPoints points) {
		return points.values() + "/" + points.points();
	}

	/**
	 * One table per service, a row per metric whose call was read, in {@link DossierGatherer#RESOURCE_METRICS} order;
	 * before it, the service's failed calls, one line per outcome and reason as written, and no table when none was
	 * read.
	 */
	private static void resources(Out out, List<ServiceResources> resources) {
		out.section("## Resources");
		out.line(LineFact.notCompared(LineFact.HEADER, Section.RESOURCES), "Per service, from query_metric "
				+ String.join(", ", DossierGatherer.RESOURCE_METRICS)
				+ ", in the units of the source: one call per service and metric over both windows, its points split as"
				+ " the latency's. \"mean\", \"max\" and \"points\" as in the latency table.");
		for (ServiceResources service : resources) {
			LineFact noted = LineFact.notCompared(service.service(), Section.RESOURCES);
			out.section("### " + cell(service.service()));
			List<String> failures = failedResources(service);
			failures.forEach(line -> out.line(noted, line));
			List<List<String>> rows = new ArrayList<>();
			List<LineFact> facts = new ArrayList<>();
			List<String> truncated = new ArrayList<>();
			for (MetricSeries metric : service.metrics()) {
				if (metric.series() instanceof Gathered.Ok<ResourceSeries> ok) {
					ResourceSeries series = ok.value();
					rows.add(List.of(cell(metric.metric()), value(series.previous().mean()),
							value(series.incident().mean()), value(series.previous().max()),
							value(series.incident().max()), points(series.previous()), points(series.incident())));
					facts.add(new LineFact(service.service(), Section.RESOURCES, WindowChange.resource(series)));
					if (series.truncated()) {
						truncated.add(cell(metric.metric()));
					}
				}
			}
			if (rows.isEmpty()) {
				continue;
			}
			if (!failures.isEmpty()) {
				out.blank();
			}
			out.table(List.of("metric", "previous mean", "incident mean", "previous max", "incident max",
					"previous points", "incident points"), rows, facts);
			if (!truncated.isEmpty()) {
				out.blank();
				out.line(noted, "query_metric " + String.join(", ", truncated) + ": truncated=true");
			}
		}
	}

	/**
	 * A service's failed resource calls, one line per outcome and reason as written, with its metrics:
	 * {@code <metrics>: query_metric failed: outcome=... reason="..."}.
	 */
	static List<String> failedResources(ServiceResources service) {
		Map<String, List<String>> metrics = new LinkedHashMap<>();
		for (MetricSeries metric : service.metrics()) {
			if (metric.series() instanceof Gathered.Failed<ResourceSeries> failed) {
				String fact = failed.tool() + " failed: outcome=" + failed.outcome() + " reason="
						+ quote(Objects.requireNonNullElse(failed.reason(), ""), RESOURCE_REASON_CHARS);
				metrics.computeIfAbsent(fact, k -> new ArrayList<>()).add(cell(metric.metric()));
			}
		}
		List<String> lines = new ArrayList<>();
		metrics.forEach((fact, names) -> lines.add(String.join(", ", names) + ": " + fact));
		return lines;
	}

	/**
	 * A resource value with {@link #RESOURCE_DIGITS} significant digits, half up, without trailing zeros, whatever the
	 * locale; plain below {@link #PLAIN_BELOW}, scientific above; {@code -} when absent.
	 */
	static String value(Double value) {
		if (value == null) {
			return NO_VALUE;
		}
		BigDecimal rounded = BigDecimal.valueOf(value)
			.round(new MathContext(RESOURCE_DIGITS, RoundingMode.HALF_UP))
			.stripTrailingZeros();
		return rounded.abs().compareTo(PLAIN_BELOW) < 0 ? rounded.toPlainString() : rounded.toString();
	}

	private static void logs(Out out, List<ServiceLogs> logs) {
		out.section("## Log signatures (incident window)");
		Map<LevelLogs, Integer> shown = shownSignatures(logs);
		for (ServiceLogs service : logs) {
			out.section("### " + cell(service.service()));
			// Logs are gathered for the incident window only, so no log line is compared (ADR-028, Decision 5).
			LineFact noted = LineFact.notCompared(service.service(), Section.LOGS);
			for (LevelLogs level : service.levels()) {
				levelLines(level, shown.getOrDefault(level, 0)).forEach(line -> out.line(noted, line));
			}
		}
	}

	/**
	 * The lines of one level of one service's logs: its counts, then its first {@code shown} signatures, each with its
	 * count, newest time and newest message, then how many are not shown.
	 */
	static List<String> levelLines(LevelLogs level, int shown) {
		List<String> lines = new ArrayList<>();
		String count = switch (level.count()) {
			case Gathered.Ok<Long> ok -> "count_logs=" + ok.value();
			case Gathered.Failed<Long> failed -> "count_logs failed " + outcome(failed);
		};
		lines.add("- " + cell(level.level()) + ": " + count + "; " + switch (level.lines()) {
			case Gathered.Ok<LogLines> ok -> "search_logs returned=" + ok.value().returned() + " truncated="
					+ ok.value().truncated() + " signatures=" + ok.value().signatures().size();
			case Gathered.Failed<LogLines> failed -> "search_logs failed " + outcome(failed);
		});
		if (level.lines() instanceof Gathered.Ok<LogLines> ok) {
			List<LogSignature> signatures = ok.value().signatures();
			signatures.stream()
				.limit(shown)
				.forEach(s -> lines.add("  - count=" + s.count() + " newest="
						+ (s.newest() == null ? NO_VALUE : s.newest().toString()) + " untrustedText="
						+ quote(s.message().text(), MESSAGE_CHARS)));
			if (signatures.size() > shown) {
				List<LogSignature> hidden = signatures.subList(shown, signatures.size());
				lines.add("  - not shown: signatures=" + hidden.size() + " lines="
						+ hidden.stream().mapToLong(LogSignature::count).sum());
			}
		}
		return lines;
	}

	/**
	 * How many signatures each level shows: rounds of one per level, in the order of {@code logs}, within both caps.
	 */
	static Map<LevelLogs, Integer> shownSignatures(List<ServiceLogs> logs) {
		List<LevelLogs> levels = logs.stream().flatMap(service -> service.levels().stream()).toList();
		Map<LevelLogs, Integer> shown = new IdentityHashMap<>();
		int total = 0;
		for (int round = 0; round < SIGNATURES; round++) {
			for (LevelLogs level : levels) {
				if (total < SIGNATURES_IN_SECTION && level.lines() instanceof Gathered.Ok<LogLines> ok
						&& ok.value().signatures().size() > round) {
					shown.merge(level, 1, Integer::sum);
					total++;
				}
			}
		}
		return shown;
	}

	private static void events(Out out, List<SourceEvents> events, Topology topology) {
		out.section("## Event counts");
		for (SourceEvents source : events) {
			String owner = topology.ownerOfSource(source.source()).orElse(LineFact.HEADER);
			out.section("### " + cell(source.source()) + " events");
			Compared<EventCounts> windows = source.windows();
			failedWindows(out, windows, LineFact.notCompared(owner, Section.EVENTS));
			// The totals are one fact with a count per window, compared as a row is.
			out.line(new LineFact(owner, Section.EVENTS, WindowChange.counts(total(windows.previous()),
					total(windows.incident()))),
					"Total: previous " + count(total(windows.previous())) + ", incident "
							+ count(total(windows.incident())) + ".");
			out.blank();
			List<Row> rows = rows(windows, counts -> counts.groups()
				.stream()
				.map((EventGroup g) -> Map.entry(Arrays.asList(g.type(), g.outcome()), g.count()))
				.toList());
			out.countTable(windows, List.of("type", "outcome"), List.of(), rows, row -> List.of(),
					counted(owner, Section.EVENTS));
		}
	}

	/** A window's total; {@code null} when the window was not read. */
	static Long total(Gathered<EventCounts> counts) {
		return counts instanceof Gathered.Ok<EventCounts> ok ? ok.value().total() : null;
	}

	/** One fact line, then a blank line, per failed window of a compared section, each line's fact {@code fact}. */
	private static void failedWindows(Out out, Compared<?> windows, LineFact fact) {
		if (windows.previous() instanceof Gathered.Failed<?> failed) {
			out.line(fact, failed(failed, "previous"));
			out.blank();
		}
		if (windows.incident() instanceof Gathered.Failed<?> failed) {
			out.line(fact, failed(failed, "incident"));
			out.blank();
		}
	}

	/** A failed call as one fact: {@code <tool> failed[ for the <window> window]: outcome=... reason="..."}. */
	static String failed(Gathered.Failed<?> failed, String window) {
		return failed.tool() + " failed" + (window == null ? "" : " for the " + window + " window") + ": "
				+ outcome(failed);
	}

	static String outcome(Gathered.Failed<?> failed) {
		return "outcome=" + failed.outcome() + " reason=" + quote(Objects.requireNonNullElse(failed.reason(), ""),
				REASON_CHARS);
	}

	/**
	 * The rows of both windows, keyed by their labels, with the counts of equal labels summed; a window that failed
	 * has {@code null} counts. Sorted by {@link #ROW_ORDER}.
	 */
	static <T> List<Row> rows(Compared<T> windows, Function<T, List<Map.Entry<List<String>, Long>>> entries) {
		Map<List<String>, long[]> counts = new LinkedHashMap<>();
		add(windows.previous(), entries, counts, 0);
		add(windows.incident(), entries, counts, 1);
		boolean previousOk = windows.previous() instanceof Gathered.Ok;
		boolean incidentOk = windows.incident() instanceof Gathered.Ok;
		List<Row> rows = new ArrayList<>();
		counts.forEach((labels, c) -> rows.add(new Row(labels, previousOk ? c[0] : null, incidentOk ? c[1] : null)));
		rows.sort(ROW_ORDER);
		return rows;
	}

	private static <T> void add(Gathered<T> window, Function<T, List<Map.Entry<List<String>, Long>>> entries,
			Map<List<String>, long[]> counts, int index) {
		if (window instanceof Gathered.Ok<T> ok) {
			for (Map.Entry<List<String>, Long> entry : entries.apply(ok.value())) {
				counts.computeIfAbsent(entry.getKey(), k -> new long[2])[index] += entry.getValue();
			}
		}
	}

	static List<Row> capped(List<Row> rows) {
		return rows.size() > ROWS ? rows.subList(0, ROWS) : rows;
	}

	/** Adds the counts of the rows past the cap to {@code sums}. */
	static void sum(List<Row> rows, long[] sums) {
		for (Row row : rows.subList(Math.min(ROWS, rows.size()), rows.size())) {
			sums[0] += row.previousOrZero();
			sums[1] += row.incidentOrZero();
		}
	}

	/**
	 * The fact of a line that sums the rows past a cap: not compared, since a sum of rows is no one row's fact, in the
	 * rows' section, and owned by their one owner, or by the header when they have several (ADR-028, Decision 3: a line
	 * with no single service).
	 */
	static LineFact hidden(List<Row> rows, Function<Row, LineFact> fact) {
		List<LineFact> facts = rows.stream().map(fact).toList();
		List<String> owners = facts.stream().map(LineFact::owner).distinct().toList();
		return LineFact.notCompared(owners.size() == 1 ? owners.get(0) : LineFact.HEADER, facts.get(0).section());
	}

	private static String notShown(int rows, Compared<?> windows, long[] sums) {
		return "not shown: rows=" + rows + ", previous="
				+ (windows.previous() instanceof Gathered.Ok ? String.valueOf(sums[0]) : NO_VALUE) + ", incident="
				+ (windows.incident() instanceof Gathered.Ok ? String.valueOf(sums[1]) : NO_VALUE);
	}

	private static Long zeroOrNull(Gathered<?> window) {
		return window instanceof Gathered.Ok ? 0L : null;
	}

	private static int minutes(Dossier.Window window) {
		return window.sinceMinutes() - window.untilMinutesAgo();
	}

	/** The newest {@link #START_TIMES} start times, newest first, and how many earlier ones are left out. */
	static String startTimes(List<Instant> times) {
		if (times.isEmpty()) {
			return NO_VALUE;
		}
		List<String> shown = new ArrayList<>();
		List<Instant> newestFirst = times.stream().sorted(Comparator.reverseOrder()).toList();
		newestFirst.stream().limit(START_TIMES).forEach(time -> shown.add(time.toString()));
		if (newestFirst.size() > START_TIMES) {
			shown.add((newestFirst.size() - START_TIMES) + " earlier");
		}
		return String.join(", ", shown);
	}

	/** {@code 1 h 59 min 49.3 s}: hours and minutes only when not zero, seconds with up to 3 decimals. */
	static String duration(Duration duration) {
		String sign = duration.isNegative() ? "-" : "";
		long millis = duration.abs().toMillis();
		long hours = millis / 3_600_000;
		long minutes = millis / 60_000 % 60;
		long seconds = millis / 1_000 % 60;
		long fraction = millis % 1_000;
		StringBuilder text = new StringBuilder(sign);
		if (hours > 0) {
			text.append(hours).append(" h ");
		}
		if (hours > 0 || minutes > 0) {
			text.append(minutes).append(" min ");
		}
		text.append(seconds);
		if (fraction > 0) {
			String digits = String.valueOf(1_000 + fraction).substring(1);
			text.append('.').append(digits.replaceAll("0+$", ""));
		}
		return text.append(" s").toString();
	}

	/**
	 * {@code text} as a JSON string literal cut at its end to at most {@code max} characters (never inside a surrogate
	 * pair), followed by {@code (cut)} when cut: the same literal as the tool summaries' ({@code SummaryText.quote}),
	 * so text from the lab can never close it or start a line of its own.
	 */
	static String quote(String text, int max) {
		boolean cut = text.length() > max;
		String kept = cut ? text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max) : text;
		return JSON.writeValueAsString(kept) + (cut ? "(cut)" : "");
	}

	/**
	 * A value in a table cell: JSON-escaped without its quotes, so a newline stays {@code \n}, with {@code |} escaped
	 * for Markdown and cut at {@link #VALUE_CHARS} characters; {@code -} when absent.
	 */
	static String cell(String value) {
		if (value == null) {
			return NO_VALUE;
		}
		String quoted = quote(value, VALUE_CHARS);
		boolean cut = quoted.endsWith("(cut)");
		String inner = quoted.substring(1, quoted.length() - (cut ? 6 : 1));
		return inner.replace("|", "\\|") + (cut ? "(cut)" : "");
	}

	static String count(Long count) {
		return count == null ? NO_VALUE : String.valueOf(count);
	}

	/** A table row: its labels and its counts in the previous and incident windows, {@code null} when not read. */
	record Row(List<String> labels, Long previous, Long incident) {

		long previousOrZero() {
			return this.previous == null ? 0 : this.previous;
		}

		long incidentOrZero() {
			return this.incident == null ? 0 : this.incident;
		}

	}

	/**
	 * The text being written; sections are separated by one blank line. Every line written with {@link #line} is
	 * citable and gets the next number, {@code D1 }, {@code D2 }, ... from the top; headings, blank lines and table
	 * header and separator rows are not numbered (evidence-by-reference). Each numbered line is written with its
	 * {@link LineFact}, so the index has one fact per line by construction.
	 */
	private static final class Out {

		private final StringBuilder text = new StringBuilder();

		private final List<LineFact> facts = new ArrayList<>();

		void line(LineFact fact, String line) {
			this.facts.add(fact);
			this.text.append(linePrefix(this.facts.size())).append(line).append('\n');
		}

		/** A heading, or a table's header or separator row: not citable, so not numbered. */
		void heading(String line) {
			this.text.append(line).append('\n');
		}

		void blank() {
			this.text.append('\n');
		}

		/** A blank line, the heading and a blank line. */
		void section(String heading) {
			if (!this.text.isEmpty() && !this.text.toString().endsWith("\n\n")) {
				blank();
			}
			heading(heading);
			blank();
		}

		/** A table; {@code facts.get(i)} is the fact of {@code rows.get(i)}. */
		void table(List<String> headers, List<List<String>> rows, List<LineFact> facts) {
			if (facts.size() != rows.size()) {
				throw new IllegalArgumentException("one fact per row: " + facts.size() + " for " + rows.size());
			}
			heading("| " + String.join(" | ", headers) + " |");
			heading("|" + "---|".repeat(headers.size()));
			for (int i = 0; i < rows.size(); i++) {
				line(facts.get(i), "| " + String.join(" | ", rows.get(i)) + " |");
			}
		}

		/**
		 * A table of labelled rows with their previous and incident counts, then {@code extra} columns; at most
		 * {@link #ROWS} rows, the others counted in one line; a "no rows" row when there are none. A row's fact is
		 * {@code fact}'s; the line counting the others sums several rows, so it is not compared (see {@link #hidden}).
		 */
		void countTable(Compared<?> windows, List<String> labelHeaders, List<String> extraHeaders, List<Row> rows,
				Function<Row, List<String>> extra, Function<Row, LineFact> fact) {
			List<Row> shown = rows;
			if (rows.isEmpty()) {
				List<String> labels = new ArrayList<>();
				labels.add(NO_ROWS);
				while (labels.size() < labelHeaders.size()) {
					labels.add(null);
				}
				shown = List.of(new Row(labels, zeroOrNull(windows.previous()), zeroOrNull(windows.incident())));
			}
			rawCountTable(labelHeaders, extraHeaders, capped(shown), extra, fact);
			if (rows.size() > ROWS) {
				long[] sums = new long[2];
				sum(rows, sums);
				blank();
				line(hidden(rows.subList(ROWS, rows.size()), fact), notShown(rows.size() - ROWS, windows, sums));
			}
		}

		void rawCountTable(List<String> labelHeaders, List<String> extraHeaders, List<Row> rows,
				Function<Row, List<String>> extra, Function<Row, LineFact> fact) {
			List<String> headers = new ArrayList<>(labelHeaders);
			headers.add("previous");
			headers.add("incident");
			headers.addAll(extraHeaders);
			List<List<String>> cells = new ArrayList<>();
			for (Row row : rows) {
				List<String> line = new ArrayList<>();
				row.labels().forEach(label -> line.add(cell(label)));
				line.add(count(row.previous()));
				line.add(count(row.incident()));
				line.addAll(extra.apply(row));
				cells.add(line);
			}
			table(headers, cells, rows.stream().map(fact).toList());
		}

		String text() {
			return this.text.toString();
		}

	}

}
