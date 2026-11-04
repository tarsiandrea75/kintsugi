package dev.kintsugi.agent.dossier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import dev.kintsugi.agent.dossier.Topology.Edge;
import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.EventCounts;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.MetricSeries;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;
import dev.kintsugi.agent.dossier.Dossier.ServiceResources;
import dev.kintsugi.agent.dossier.Dossier.ServiceLatency;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.SourceEvents;
import dev.kintsugi.agent.dossier.Dossier.TableStates;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.dossier.Dossier.UriLatency;
import dev.kintsugi.agent.dossier.DossierRenderer.Row;

/**
 * Writes a {@link Dossier} as one fact sheet per lab service, leaf first (agent spec, "Evidence dossier rendering", the
 * sheets; odd/tasks/service-assessment.md T1): the facts {@link DossierRenderer} writes as tables by data source,
 * regrouped by the service each belongs to, in {@link Topology#leafFirst()} order, each service after the services it
 * calls, so that a reader can weigh one service at a time next to the call graph. Like the tables it writes facts only,
 * in the tables' own words where they say the same thing, numbers every citable line with the same {@code D<n> }
 * prefix, so {@link DossierRenderer#line} reads the sheets too, and gives the same text for the same dossier. It also
 * returns the service that owns each numbered line, and its {@link LineFact}.
 *
 * <p>
 * A fact belongs to a service by the data: restarts, latency and logs carry their service; a traffic row on the client
 * side belongs to its caller ({@code service}), with the callee in {@code clientName}, and one on the server side to
 * the callee; a state table or an event source belongs to the service the {@link Topology} names for it. A sheet holds,
 * in order: the service's place in the graph, its restarts, its inbound requests, the latency of its uris but
 * {@code /actuator/*}, its resources, its outbound calls per edge, its log signatures, and the tables and events it owns. A count row
 * whose incident count is 0 while its previous count is above 0 is written in the last section, "Seen only in the
 * previous window", under its service, which still owns it. The tables' caps hold per block. A failed call that covers
 * every service ({@code service_restarts}, {@code traffic_counts}) is one fact line of the header; any other is one line
 * in its service's sheet. What no lab service owns, a traffic row of no lab service or a state table or an event source
 * the {@link Topology} names no owner for, is kept under "Other rows", owned by the header, every row in place.
 */
public final class ServiceSheets {

	/**
	 * The owner of the numbered lines that state no lab service's fact: the header's, the line of an empty last section,
	 * and the "Other rows", which hold what no lab service owns.
	 */
	public static final String HEADER = LineFact.HEADER;

	/** The title of the last section. */
	static final String PREVIOUS_ONLY = "Seen only in the previous window";

	/** The uris the latency leaves out: the actuator's, which Prometheus scrapes, not the shop's clients. */
	private static final String ACTUATOR = "/actuator";

	private ServiceSheets() {
	}

	/**
	 * The sheets as written, the owner of each numbered line, {@code owners.get(n - 1)} owning line {@code D<n>}, a lab
	 * service or {@link #HEADER}, and its fact, {@code facts.get(n - 1)}, whose owner is the same (ADR-028, Decision 3).
	 * {@code facts} is empty only for sheets built from a text and its owners alone, which index no fact.
	 */
	public record Rendered(String text, List<String> owners, List<LineFact> facts) {

		public Rendered {
			owners = List.copyOf(owners);
			facts = List.copyOf(facts);
			if (!facts.isEmpty() && !facts.stream().map(LineFact::owner).toList().equals(owners)) {
				throw new IllegalArgumentException("the facts' owners must be the lines' owners");
			}
		}

		/** Sheets with their owners and no fact index. */
		public Rendered(String text, List<String> owners) {
			this(text, owners, List.of());
		}

		/** The fact of line {@code D<n>}; empty when the sheets have no such line or index no fact. */
		public Optional<LineFact> fact(int n) {
			return n < 1 || n > this.facts.size() ? Optional.empty() : Optional.of(this.facts.get(n - 1));
		}

		/** The owner of line {@code D<n>}; empty when the sheets have no such line. */
		public Optional<String> owner(int n) {
			return n < 1 || n > this.owners.size() ? Optional.empty() : Optional.of(this.owners.get(n - 1));
		}

	}

	/** The sheets of {@code dossier} over {@code topology}'s services, ending with a newline. */
	public static Rendered render(Dossier dossier, Topology topology) {
		List<String> services = topology.leafFirst();
		Out out = new Out();
		header(out, dossier);
		List<ServiceLogs> logs = dossier.logs()
			.stream()
			.filter(service -> services.contains(service.service()))
			.sorted(Comparator.comparingInt(service -> services.indexOf(service.service())))
			.toList();
		// Handed out in the sheets' order, so the leaf's levels come first when the section's cap binds.
		Map<LevelLogs, Integer> shown = DossierRenderer.shownSignatures(logs);
		Map<String, List<Written>> later = new LinkedHashMap<>();
		for (String service : services) {
			out.section("## " + service);
			Sheet sheet = Sheet.of(service, out);
			// The service's place in the call graph states no window's fact.
			sheet.noted(Section.HEADER, "- calls: " + names(topology.calls(service)) + "; called by: "
					+ names(topology.calledBy(service)) + ".");
			restarts(sheet, dossier.services());
			inbound(sheet, dossier.traffic());
			latency(sheet, dossier.latency());
			resources(sheet, dossier.resources());
			outbound(sheet, dossier.traffic(), topology.edges());
			logs(sheet, logs, shown);
			for (TableStates table : dossier.states()) {
				if (topology.ownerOfTable(table.table()).filter(service::equals).isPresent()) {
					states(sheet, table);
				}
			}
			for (SourceEvents source : dossier.events()) {
				if (topology.ownerOfSource(source.source()).filter(service::equals).isPresent()) {
					events(sheet, source);
				}
			}
			later.put(service, sheet.later);
		}
		lastSection(out, later);
		others(out, dossier, topology);
		return new Rendered(out.text(), out.owners(), out.facts);
	}

	private static void header(Out out, Dossier dossier) {
		LineFact header = LineFact.notCompared(HEADER, Section.HEADER);
		out.heading("# Evidence dossier, by service");
		out.blank();
		DossierRenderer.windowLines(dossier).forEach(line -> out.line(header, line));
		out.line(header, "- Counts are written \"incident N (previous M)\", N in the incident window and M in the "
				+ "previous one. \"" + DossierRenderer.NO_VALUE + "\" means no value.");
		out.line(header, DossierRenderer.UNTRUSTED_TEXT_LINE);
		out.line(header, "- One section per service, each after the services it calls; then the rows seen only in the "
				+ "previous window, under their service.");
		out.line(header, "- inbound: the requests a service answered; outbound: the calls it made; both from "
				+ "traffic_counts, which leaves out rows of count 0.");
		out.line(header, "- latency: seconds per request on the server side, from query_metric "
				+ DossierGatherer.LATENCY_METRIC + "; the mean and max of the points with a value in each window. "
				+ ACTUATOR + "/* uris are left out.");
		out.line(header, "- resources: " + String.join(", ", DossierGatherer.RESOURCE_METRICS.subList(0,
				DossierGatherer.RESOURCE_METRICS.size() - 1)) + " and "
				+ DossierGatherer.RESOURCE_METRICS.get(DossierGatherer.RESOURCE_METRICS.size() - 1)
				+ " from query_metric, in the units of the source; the mean and max of the points with a value in each"
				+ " window.");
		if (dossier.services() instanceof Gathered.Failed<?> failed) {
			out.line(LineFact.notCompared(HEADER, Section.STATUS), "- " + DossierRenderer.failed(failed, null));
		}
		failedWindows(dossier.traffic(), "")
			.forEach(line -> out.line(LineFact.notCompared(HEADER, Section.TRAFFIC), line));
	}

	private static void restarts(Sheet sheet, Gathered<List<ServiceStatus>> services) {
		Optional<ServiceStatus> status = services instanceof Gathered.Ok<List<ServiceStatus>> ok
				? find(ok.value(), ServiceStatus::service, sheet.service) : Optional.empty();
		if (status.isEmpty()) {
			sheet.noted(Section.STATUS, "- restarts in the incident window: -; uptime at gathering: -.");
			return;
		}
		List<Instant> restarts = status.get().restartsInWindow();
		String times = restarts.isEmpty() ? "" : " (" + DossierRenderer.startTimes(restarts) + ")";
		String uptime = status.get().uptime() == null ? DossierRenderer.NO_VALUE
				: DossierRenderer.duration(status.get().uptime());
		sheet.line(Section.STATUS, WindowChange.restarts(status.get()), "- restarts in the incident window: "
				+ restarts.size() + times + "; uptime at gathering: " + uptime
				+ ".");
	}

	/** The requests the service answered: its server-side rows, whatever their uri. */
	private static void inbound(Sheet sheet, Compared<List<TrafficRow>> traffic) {
		block(sheet, Section.TRAFFIC, "inbound", traffic,
				trafficRows(traffic, row -> "server".equals(row.side()) && sheet.service.equals(row.service()),
						row -> Arrays.asList(row.method(), row.uri(), row.status(), row.outcome(), row.exception())),
				labels -> words(cell(labels.get(0)), cell(labels.get(1)), key("status", labels.get(2)),
						key("outcome", labels.get(3)), key("exception", labels.get(4))),
				true);
	}

	/**
	 * The calls the service made, its client-side rows: one block per edge of the call graph it calls on, named by its
	 * callee alone, since the graph has one edge per caller and callee and the callee's inbound rows name the uri; then
	 * one block for any other call, with its callee, method and uri, written only when there is one.
	 */
	private static void outbound(Sheet sheet, Compared<List<TrafficRow>> traffic, List<Edge> edges) {
		String service = sheet.service;
		for (Edge edge : edges) {
			if (service.equals(edge.caller())) {
				block(sheet, Section.TRAFFIC, "outbound to " + edge.target(), traffic,
						trafficRows(traffic, edge::callerRow,
								row -> Arrays.asList(row.status(), row.outcome(), row.exception())),
						labels -> words(key("status", labels.get(0)), key("outcome", labels.get(1)),
								key("exception", labels.get(2))),
						true);
			}
		}
		block(sheet, Section.TRAFFIC, "outbound", traffic,
				trafficRows(traffic,
						row -> "client".equals(row.side()) && service.equals(row.service())
								&& edges.stream().noneMatch(edge -> edge.callerRow(row)),
						row -> Arrays.asList(row.clientName(), row.method(), row.uri(), row.status(), row.outcome(),
								row.exception())),
				labels -> words("to " + cell(labels.get(0)), cell(labels.get(1)), cell(labels.get(2)),
						key("status", labels.get(3)), key("outcome", labels.get(4)), key("exception", labels.get(5))),
				false);
	}

	/**
	 * The latency of the service's uris, {@code /actuator/*} left out, sorted as the tables sort them and capped as they
	 * are: incident mean and max, and the previous mean.
	 */
	private static void latency(Sheet sheet, List<ServiceLatency> latency) {
		Optional<ServiceLatency> found = find(latency, ServiceLatency::service, sheet.service);
		if (found.isEmpty()) {
			sheet.noted(Section.LATENCY, "- latency: not gathered.");
			return;
		}
		switch (found.get().latency()) {
			case Gathered.Failed<LatencySeries> failed ->
				sheet.noted(Section.LATENCY, "- latency: " + DossierRenderer.failed(failed, null));
			case Gathered.Ok<LatencySeries> ok -> {
				List<UriLatency> uris = ok.value()
					.uris()
					.stream()
					.filter(uri -> !actuator(uri.uri()))
					.sorted(DossierRenderer.URI_ORDER)
					.toList();
				if (uris.isEmpty()) {
					sheet.noted(Section.LATENCY, "- latency: no rows.");
				}
				for (UriLatency uri : uris.subList(0, Math.min(DossierRenderer.LATENCY_ROWS, uris.size()))) {
					sheet.line(Section.LATENCY, WindowChange.latency(uri.previous(), uri.incident()),
							"- latency " + cell(uri.uri()) + ": incident mean " + seconds(uri.incident().mean())
							+ ", max " + seconds(uri.incident().max()) + " (previous mean "
							+ seconds(uri.previous().mean()) + ").");
				}
				if (uris.size() > DossierRenderer.LATENCY_ROWS) {
					sheet.noted(Section.LATENCY,
							"- latency not shown: rows=" + (uris.size() - DossierRenderer.LATENCY_ROWS) + ".");
				}
				if (ok.value().truncated()) {
					sheet.noted(Section.LATENCY, "- latency: query_metric truncated=true.");
				}
			}
		}
	}

	/**
	 * The service's resources as the tables write them: its failed calls first, one line per outcome and reason, then
	 * a line per metric read, incident mean and max, and the previous ones, then one line naming the truncated ones.
	 */
	private static void resources(Sheet sheet, List<ServiceResources> resources) {
		Optional<ServiceResources> found = find(resources, ServiceResources::service, sheet.service);
		if (found.isEmpty()) {
			sheet.noted(Section.RESOURCES, "- resources: not gathered.");
			return;
		}
		DossierRenderer.failedResources(found.get())
			.forEach(line -> sheet.noted(Section.RESOURCES, "- resources " + line));
		List<String> truncated = new ArrayList<>();
		for (MetricSeries metric : found.get().metrics()) {
			if (metric.series() instanceof Gathered.Ok<ResourceSeries> ok) {
				ResourceSeries series = ok.value();
				sheet.line(Section.RESOURCES, WindowChange.resource(series), "- resource " + cell(metric.metric())
						+ ": incident mean "
						+ DossierRenderer.value(series.incident().mean()) + ", max "
						+ DossierRenderer.value(series.incident().max()) + " (previous mean "
						+ DossierRenderer.value(series.previous().mean()) + ", max "
						+ DossierRenderer.value(series.previous().max()) + ").");
				if (series.truncated()) {
					truncated.add(cell(metric.metric()));
				}
			}
		}
		if (!truncated.isEmpty()) {
			sheet.noted(Section.RESOURCES,
					"- resources " + String.join(", ", truncated) + ": query_metric truncated=true.");
		}
	}

	private static boolean actuator(String uri) {
		return uri != null && (uri.equals(ACTUATOR) || uri.startsWith(ACTUATOR + "/"));
	}

	/** The service's log levels as the tables write them, its signatures within the section's caps. */
	private static void logs(Sheet sheet, List<ServiceLogs> logs, Map<LevelLogs, Integer> shown) {
		Optional<ServiceLogs> found = find(logs, ServiceLogs::service, sheet.service);
		if (found.isEmpty()) {
			sheet.noted(Section.LOGS, "- logs: not gathered.");
			return;
		}
		for (LevelLogs level : found.get().levels()) {
			// Logs are gathered for the incident window only, so no log line is compared (ADR-028, Decision 5).
			DossierRenderer.levelLines(level, shown.getOrDefault(level, 0))
				.forEach(line -> sheet.noted(Section.LOGS, line));
		}
	}

	private static void states(Sheet sheet, TableStates table) {
		String label = cell(table.table()) + " table";
		failedWindows(table.windows(), label + ": ").forEach(line -> sheet.noted(Section.STATES, line));
		block(sheet, Section.STATES, label, table.windows(), DossierRenderer.rows(table.windows(), list -> list.stream()
			.map(s -> Map.entry(Arrays.asList(s.status()), s.count()))
			.toList()), labels -> key("status", labels.get(0)), true);
	}

	/**
	 * The source's failed windows, its totals, then its rows. The totals line states the incident window's count, so
	 * the block writes no line of its own when no row is left.
	 */
	private static void events(Sheet sheet, SourceEvents source) {
		String label = cell(source.source()) + " events";
		Compared<EventCounts> windows = source.windows();
		failedWindows(windows, label + ": ").forEach(line -> sheet.noted(Section.EVENTS, line));
		// The totals are one fact with a count per window, compared as a row is.
		Long previous = DossierRenderer.total(windows.previous());
		Long incident = DossierRenderer.total(windows.incident());
		sheet.line(Section.EVENTS, WindowChange.counts(previous, incident),
				"- " + label + ": total " + counts(incident, previous) + ".");
		block(sheet, Section.EVENTS, label, windows, DossierRenderer.rows(windows, counts -> counts.groups()
			.stream()
			.map(g -> Map.entry(Arrays.asList(g.type(), g.outcome()), g.count()))
			.toList()), labels -> words(key("type", labels.get(0)), key("outcome", labels.get(1))), false);
	}

	/**
	 * Writes one block of count rows in the sheet: those not seen only in the previous window, at most
	 * {@link DossierRenderer#ROWS}, then the others' number and counts; with none, and {@code empty}, one line saying
	 * so. The rows seen only in the previous window go to the sheet's part of the last section, capped the same way;
	 * under "Other rows", which no service owns, they stay in place with the others.
	 * @param text a row's labels as written after the block's label
	 */
	private static void block(Sheet sheet, Section section, String label, Compared<?> windows, List<Row> rows,
			Function<List<String>, String> text, boolean empty) {
		List<Row> current = sheet.later == null ? rows
				: rows.stream().filter(Predicate.not(ServiceSheets::previousOnly)).toList();
		if (current.isEmpty() && empty) {
			boolean read = windows.incident() instanceof Gathered.Ok;
			sheet.noted(section, "- " + label + ": " + (read ? "no rows in the incident window." : "no rows read."));
		}
		counted(label, section, windows, current, text).forEach(sheet::write);
		if (sheet.later != null) {
			sheet.later.addAll(counted(label, section, windows,
					rows.stream().filter(ServiceSheets::previousOnly).toList(), text));
		}
	}

	/** Whether a row was counted in the previous window only: 0 in the incident window, above 0 in the previous one. */
	private static boolean previousOnly(Row row) {
		return Objects.equals(row.incident(), 0L) && row.previous() != null && row.previous() > 0;
	}

	/**
	 * The lines of {@code rows}, at most {@link DossierRenderer#ROWS}, each changed by {@link WindowChange#counts},
	 * then how many more and their counts, a sum of rows, which is not compared.
	 */
	private static List<Written> counted(String label, Section section, Compared<?> windows, List<Row> rows,
			Function<List<String>, String> text) {
		List<Written> lines = new ArrayList<>();
		for (Row row : DossierRenderer.capped(rows)) {
			lines.add(new Written("- " + words(label, text.apply(row.labels())) + ": "
					+ counts(row.incident(), row.previous()) + ".", section,
					WindowChange.counts(row.previous(), row.incident())));
		}
		if (rows.size() > DossierRenderer.ROWS) {
			long[] sums = new long[2];
			DossierRenderer.sum(rows, sums);
			lines.add(new Written("- " + words(label, "not shown: rows=" + (rows.size() - DossierRenderer.ROWS) + ", "
					+ counts(windows.incident() instanceof Gathered.Ok ? sums[1] : null,
							windows.previous() instanceof Gathered.Ok ? sums[0] : null)
					+ "."), section, Change.NOT_COMPARED));
		}
		return lines;
	}

	/** The last section: per service, in the sheets' order, the rows it saw only in the previous window. */
	private static void lastSection(Out out, Map<String, List<Written>> later) {
		out.section("## " + PREVIOUS_ONLY);
		if (later.values().stream().allMatch(List::isEmpty)) {
			out.line(LineFact.notCompared(HEADER, Section.HEADER), "- no rows.");
			return;
		}
		later.forEach((service, lines) -> {
			if (!lines.isEmpty()) {
				out.section("### " + service);
				lines.forEach(line -> out.line(new LineFact(service, line.section(), line.changed()), line.text()));
			}
		});
	}

	/**
	 * What no lab service owns, under "Other rows", written only when there is one: the traffic rows of no lab service,
	 * with all their labels, then the state tables and the event sources the {@link Topology} names no owner for,
	 * each as a sheet writes it, but with every row in place, since the last section is per service.
	 * {@code traffic_counts} reads the lab's four services only, and every table and source the gatherer reads has its
	 * owner (a test checks it), so a name missing from the graph costs a sheet its facts, never the rendering.
	 */
	private static void others(Out out, Dossier dossier, Topology topology) {
		Compared<List<TrafficRow>> traffic = dossier.traffic();
		List<Row> rows = trafficRows(traffic,
				row -> !(("server".equals(row.side()) || "client".equals(row.side()))
						&& topology.services().contains(row.service())),
				row -> Arrays.asList(row.side(), row.service(), row.clientName(), row.method(), row.uri(), row.status(),
						row.outcome(), row.exception()));
		List<TableStates> tables = dossier.states()
			.stream()
			.filter(table -> topology.ownerOfTable(table.table()).isEmpty())
			.toList();
		List<SourceEvents> sources = dossier.events()
			.stream()
			.filter(source -> topology.ownerOfSource(source.source()).isEmpty())
			.toList();
		if (rows.isEmpty() && tables.isEmpty() && sources.isEmpty()) {
			return;
		}
		out.section("## Other rows");
		Sheet others = Sheet.others(out);
		counted("", Section.TRAFFIC, traffic, rows,
				labels -> words(key("side", labels.get(0)), key("service", labels.get(1)), key("client", labels.get(2)),
						cell(labels.get(3)), cell(labels.get(4)), key("status", labels.get(5)),
						key("outcome", labels.get(6)), key("exception", labels.get(7))))
			.forEach(others::write);
		tables.forEach(table -> states(others, table));
		sources.forEach(source -> events(others, source));
	}

	/** The traffic rows {@code which} picks, by the labels {@code labels} gives, with both windows' counts. */
	private static List<Row> trafficRows(Compared<List<TrafficRow>> traffic, Predicate<TrafficRow> which,
			Function<TrafficRow, List<String>> labels) {
		return DossierRenderer.rows(traffic,
				list -> list.stream().filter(which).map(row -> Map.entry(labels.apply(row), row.count())).toList());
	}

	/** One line per failed window of a compared call, each starting {@code - <prefix>}. */
	private static List<String> failedWindows(Compared<?> windows, String prefix) {
		List<String> lines = new ArrayList<>();
		if (windows.previous() instanceof Gathered.Failed<?> failed) {
			lines.add("- " + prefix + DossierRenderer.failed(failed, "previous"));
		}
		if (windows.incident() instanceof Gathered.Failed<?> failed) {
			lines.add("- " + prefix + DossierRenderer.failed(failed, "incident"));
		}
		return lines;
	}

	/** {@code incident N (previous M)}, with {@code -} for a window not read. */
	private static String counts(Long incident, Long previous) {
		return "incident " + DossierRenderer.count(incident) + " (previous " + DossierRenderer.count(previous) + ")";
	}

	/** Seconds with milliseconds and their unit, as the tables round them; {@code -} when absent. */
	private static String seconds(Double seconds) {
		return seconds == null ? DossierRenderer.NO_VALUE : DossierRenderer.seconds(seconds) + " s";
	}

	/** A label as {@code key=value}, the value escaped as a table cell; null when the row does not carry it. */
	private static String key(String key, String value) {
		return value == null ? null : key + "=" + cell(value);
	}

	/** The words that are not null or empty, joined by a space. */
	private static String words(String... words) {
		return String.join(" ", Arrays.stream(words).filter(word -> word != null && !word.isEmpty()).toList());
	}

	private static String cell(String value) {
		return DossierRenderer.cell(value);
	}

	private static String names(List<String> names) {
		return names.isEmpty() ? "none" : String.join(", ", names);
	}

	private static <T> Optional<T> find(List<T> items, Function<T, String> service, String name) {
		return items.stream().filter(item -> name.equals(service.apply(item))).findFirst();
	}

	/**
	 * One service's sheet: its lines, which it owns, and the rows it saw only in the previous window, for later; or the
	 * "Other rows", whose lines the header owns and which keep every row in place.
	 */
	private static final class Sheet {

		/** The service, which owns the lines; {@link #HEADER} for the "Other rows". */
		final String service;

		final Out out;

		/** The lines of the rows seen only in the previous window, for the last section; null under "Other rows". */
		final List<Written> later;

		private Sheet(String service, Out out, List<Written> later) {
			this.service = service;
			this.out = out;
			this.later = later;
		}

		static Sheet of(String service, Out out) {
			return new Sheet(service, out, new ArrayList<>());
		}

		static Sheet others(Out out) {
			return new Sheet(HEADER, out, null);
		}

		/** A line of the service's in {@code section}, changed or not as {@code changed} says. */
		void line(Section section, Change changed, String line) {
			this.out.line(new LineFact(this.service, section, changed), line);
		}

		/** A line of the service's in {@code section} whose fact is not compared. */
		void noted(Section section, String line) {
			line(section, Change.NOT_COMPARED, line);
		}

		void write(Written line) {
			line(line.section(), line.changed(), line.text());
		}

	}

	/**
	 * A count line written later or elsewhere than it is computed, with its section and change; its owner is the sheet
	 * that writes it.
	 */
	private record Written(String text, Section section, Change changed) {
	}

	/**
	 * The text being written, numbered as {@link DossierRenderer} numbers it, with the owner of every numbered line;
	 * sections are separated by one blank line.
	 */
	private static final class Out {

		private final StringBuilder text = new StringBuilder();

		private final List<LineFact> facts = new ArrayList<>();

		void line(LineFact fact, String line) {
			this.facts.add(fact);
			this.text.append(DossierRenderer.linePrefix(this.facts.size())).append(line).append('\n');
		}

		List<String> owners() {
			return this.facts.stream().map(LineFact::owner).toList();
		}

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

		String text() {
			return this.text.toString();
		}

	}

}
