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

import dev.kintsugi.agent.dossier.CallGraph.Edge;
import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.EventCounts;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
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
 * regrouped by the service each belongs to, in {@link CallGraph#SERVICES} order, each service after the services it
 * calls, so that a reader can weigh one service at a time next to the call graph. Like the tables it writes facts only,
 * in the tables' own words where they say the same thing, numbers every citable line with the same {@code D<n> }
 * prefix, so {@link DossierRenderer#line} reads the sheets too, and gives the same text for the same dossier. It also
 * returns the service that owns each numbered line.
 *
 * <p>
 * A fact belongs to a service by the data: restarts, latency and logs carry their service; a traffic row on the client
 * side belongs to its caller ({@code service}), with the callee in {@code clientName}, and one on the server side to
 * the callee; a state table or an event source belongs to the service {@link CallGraph} names for it. A sheet holds,
 * in order: the service's place in the graph, its restarts, its inbound requests, the latency of its uris but
 * {@code /actuator/*}, its outbound calls per edge, its log signatures, and the tables and events it owns. A count row
 * whose incident count is 0 while its previous count is above 0 is written in the last section, "Seen only in the
 * previous window", under its service, which still owns it. The tables' caps hold per block. A failed call that covers
 * every service ({@code service_restarts}, {@code traffic_counts}) is one fact line of the header; any other is one line
 * in its service's sheet. What no lab service owns, a traffic row of no lab service or a state table or an event source
 * {@link CallGraph} names no owner for, is kept under "Other rows", owned by the header, every row in place.
 */
public final class ServiceSheets {

	/**
	 * The owner of the numbered lines that state no lab service's fact: the header's, the line of an empty last section,
	 * and the "Other rows", which hold what no lab service owns.
	 */
	public static final String HEADER = "header";

	/** The title of the last section. */
	static final String PREVIOUS_ONLY = "Seen only in the previous window";

	/** The uris the latency leaves out: the actuator's, which Prometheus scrapes, not the shop's clients. */
	private static final String ACTUATOR = "/actuator";

	private ServiceSheets() {
	}

	/**
	 * The sheets as written, and the owner of each numbered line: {@code owners.get(n - 1)} owns line {@code D<n>}, a
	 * lab service or {@link #HEADER}.
	 */
	public record Rendered(String text, List<String> owners) {

		public Rendered {
			owners = List.copyOf(owners);
		}

		/** The owner of line {@code D<n>}; empty when the sheets have no such line. */
		public Optional<String> owner(int n) {
			return n < 1 || n > this.owners.size() ? Optional.empty() : Optional.of(this.owners.get(n - 1));
		}

	}

	/** The sheets of {@code dossier}, ending with a newline. */
	public static Rendered render(Dossier dossier) {
		Out out = new Out();
		header(out, dossier);
		List<ServiceLogs> logs = dossier.logs()
			.stream()
			.filter(service -> CallGraph.SERVICES.contains(service.service()))
			.sorted(Comparator.comparingInt(service -> CallGraph.SERVICES.indexOf(service.service())))
			.toList();
		// Handed out in the sheets' order, so the leaf's levels come first when the section's cap binds.
		Map<LevelLogs, Integer> shown = DossierRenderer.shownSignatures(logs);
		Map<String, List<String>> later = new LinkedHashMap<>();
		for (String service : CallGraph.SERVICES) {
			out.section("## " + service);
			Sheet sheet = Sheet.of(service, out);
			sheet.line("- calls: " + names(CallGraph.calls(service)) + "; called by: "
					+ names(CallGraph.calledBy(service)) + ".");
			restarts(sheet, dossier.services());
			inbound(sheet, dossier.traffic());
			latency(sheet, dossier.latency());
			outbound(sheet, dossier.traffic());
			logs(sheet, logs, shown);
			for (TableStates table : dossier.states()) {
				if (CallGraph.ownerOfTable(table.table()).filter(service::equals).isPresent()) {
					states(sheet, table);
				}
			}
			for (SourceEvents source : dossier.events()) {
				if (CallGraph.ownerOfSource(source.source()).filter(service::equals).isPresent()) {
					events(sheet, source);
				}
			}
			later.put(service, sheet.later);
		}
		lastSection(out, later);
		others(out, dossier);
		return new Rendered(out.text(), out.owners);
	}

	private static void header(Out out, Dossier dossier) {
		out.heading("# Evidence dossier, by service");
		out.blank();
		DossierRenderer.windowLines(dossier).forEach(line -> out.line(HEADER, line));
		out.line(HEADER, "- Counts are written \"incident N (previous M)\", N in the incident window and M in the "
				+ "previous one. \"" + DossierRenderer.NO_VALUE + "\" means no value.");
		out.line(HEADER, DossierRenderer.UNTRUSTED_TEXT_LINE);
		out.line(HEADER, "- One section per service, each after the services it calls; then the rows seen only in the "
				+ "previous window, under their service.");
		out.line(HEADER, "- inbound: the requests a service answered; outbound: the calls it made; both from "
				+ "traffic_counts, which leaves out rows of count 0.");
		out.line(HEADER, "- latency: seconds per request on the server side, from query_metric "
				+ DossierGatherer.LATENCY_METRIC + "; the mean and max of the points with a value in each window. "
				+ ACTUATOR + "/* uris are left out.");
		if (dossier.services() instanceof Gathered.Failed<?> failed) {
			out.line(HEADER, "- " + DossierRenderer.failed(failed, null));
		}
		failedWindows(dossier.traffic(), "").forEach(line -> out.line(HEADER, line));
	}

	private static void restarts(Sheet sheet, Gathered<List<ServiceStatus>> services) {
		Optional<ServiceStatus> status = services instanceof Gathered.Ok<List<ServiceStatus>> ok
				? find(ok.value(), ServiceStatus::service, sheet.service) : Optional.empty();
		if (status.isEmpty()) {
			sheet.line("- restarts in the incident window: -; uptime at gathering: -.");
			return;
		}
		List<Instant> restarts = status.get().restartsInWindow();
		String times = restarts.isEmpty() ? "" : " (" + DossierRenderer.startTimes(restarts) + ")";
		String uptime = status.get().uptime() == null ? DossierRenderer.NO_VALUE
				: DossierRenderer.duration(status.get().uptime());
		sheet.line("- restarts in the incident window: " + restarts.size() + times + "; uptime at gathering: " + uptime
				+ ".");
	}

	/** The requests the service answered: its server-side rows, whatever their uri. */
	private static void inbound(Sheet sheet, Compared<List<TrafficRow>> traffic) {
		block(sheet, "inbound", traffic,
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
	private static void outbound(Sheet sheet, Compared<List<TrafficRow>> traffic) {
		String service = sheet.service;
		for (Edge edge : CallGraph.EDGES) {
			if (service.equals(edge.caller())) {
				block(sheet, "outbound to " + edge.target(), traffic,
						trafficRows(traffic, edge::callerRow,
								row -> Arrays.asList(row.status(), row.outcome(), row.exception())),
						labels -> words(key("status", labels.get(0)), key("outcome", labels.get(1)),
								key("exception", labels.get(2))),
						true);
			}
		}
		block(sheet, "outbound", traffic,
				trafficRows(traffic,
						row -> "client".equals(row.side()) && service.equals(row.service())
								&& CallGraph.EDGES.stream().noneMatch(edge -> edge.callerRow(row)),
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
			sheet.line("- latency: not gathered.");
			return;
		}
		switch (found.get().latency()) {
			case Gathered.Failed<LatencySeries> failed ->
				sheet.line("- latency: " + DossierRenderer.failed(failed, null));
			case Gathered.Ok<LatencySeries> ok -> {
				List<UriLatency> uris = ok.value()
					.uris()
					.stream()
					.filter(uri -> !actuator(uri.uri()))
					.sorted(DossierRenderer.URI_ORDER)
					.toList();
				if (uris.isEmpty()) {
					sheet.line("- latency: no rows.");
				}
				for (UriLatency uri : uris.subList(0, Math.min(DossierRenderer.LATENCY_ROWS, uris.size()))) {
					sheet.line("- latency " + cell(uri.uri()) + ": incident mean " + seconds(uri.incident().mean())
							+ ", max " + seconds(uri.incident().max()) + " (previous mean "
							+ seconds(uri.previous().mean()) + ").");
				}
				if (uris.size() > DossierRenderer.LATENCY_ROWS) {
					sheet.line("- latency not shown: rows=" + (uris.size() - DossierRenderer.LATENCY_ROWS) + ".");
				}
				if (ok.value().truncated()) {
					sheet.line("- latency: query_metric truncated=true.");
				}
			}
		}
	}

	private static boolean actuator(String uri) {
		return uri != null && (uri.equals(ACTUATOR) || uri.startsWith(ACTUATOR + "/"));
	}

	/** The service's log levels as the tables write them, its signatures within the section's caps. */
	private static void logs(Sheet sheet, List<ServiceLogs> logs, Map<LevelLogs, Integer> shown) {
		Optional<ServiceLogs> found = find(logs, ServiceLogs::service, sheet.service);
		if (found.isEmpty()) {
			sheet.line("- logs: not gathered.");
			return;
		}
		for (LevelLogs level : found.get().levels()) {
			DossierRenderer.levelLines(level, shown.getOrDefault(level, 0)).forEach(sheet::line);
		}
	}

	private static void states(Sheet sheet, TableStates table) {
		String label = cell(table.table()) + " table";
		failedWindows(table.windows(), label + ": ").forEach(sheet::line);
		block(sheet, label, table.windows(), DossierRenderer.rows(table.windows(), list -> list.stream()
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
		failedWindows(windows, label + ": ").forEach(sheet::line);
		sheet.line("- " + label + ": total " + counts(total(windows.incident()), total(windows.previous())) + ".");
		block(sheet, label, windows, DossierRenderer.rows(windows, counts -> counts.groups()
			.stream()
			.map(g -> Map.entry(Arrays.asList(g.type(), g.outcome()), g.count()))
			.toList()), labels -> words(key("type", labels.get(0)), key("outcome", labels.get(1))), false);
	}

	private static Long total(Gathered<EventCounts> counts) {
		return counts instanceof Gathered.Ok<EventCounts> ok ? ok.value().total() : null;
	}

	/**
	 * Writes one block of count rows in the sheet: those not seen only in the previous window, at most
	 * {@link DossierRenderer#ROWS}, then the others' number and counts; with none, and {@code empty}, one line saying
	 * so. The rows seen only in the previous window go to the sheet's part of the last section, capped the same way;
	 * under "Other rows", which no service owns, they stay in place with the others.
	 * @param text a row's labels as written after the block's label
	 */
	private static void block(Sheet sheet, String label, Compared<?> windows, List<Row> rows,
			Function<List<String>, String> text, boolean empty) {
		List<Row> current = sheet.later == null ? rows
				: rows.stream().filter(Predicate.not(ServiceSheets::previousOnly)).toList();
		if (current.isEmpty() && empty) {
			boolean read = windows.incident() instanceof Gathered.Ok;
			sheet.line("- " + label + ": " + (read ? "no rows in the incident window." : "no rows read."));
		}
		counted(label, windows, current, text).forEach(sheet::line);
		if (sheet.later != null) {
			sheet.later.addAll(counted(label, windows,
					rows.stream().filter(ServiceSheets::previousOnly).toList(), text));
		}
	}

	/** Whether a row was counted in the previous window only: 0 in the incident window, above 0 in the previous one. */
	private static boolean previousOnly(Row row) {
		return Objects.equals(row.incident(), 0L) && row.previous() != null && row.previous() > 0;
	}

	/** The lines of {@code rows}, at most {@link DossierRenderer#ROWS}, then how many more and their counts. */
	private static List<String> counted(String label, Compared<?> windows, List<Row> rows,
			Function<List<String>, String> text) {
		List<String> lines = new ArrayList<>();
		for (Row row : DossierRenderer.capped(rows)) {
			lines.add("- " + words(label, text.apply(row.labels())) + ": " + counts(row.incident(), row.previous())
					+ ".");
		}
		if (rows.size() > DossierRenderer.ROWS) {
			long[] sums = new long[2];
			DossierRenderer.sum(rows, sums);
			lines.add("- " + words(label, "not shown: rows=" + (rows.size() - DossierRenderer.ROWS) + ", "
					+ counts(windows.incident() instanceof Gathered.Ok ? sums[1] : null,
							windows.previous() instanceof Gathered.Ok ? sums[0] : null)
					+ "."));
		}
		return lines;
	}

	/** The last section: per service, in the sheets' order, the rows it saw only in the previous window. */
	private static void lastSection(Out out, Map<String, List<String>> later) {
		out.section("## " + PREVIOUS_ONLY);
		if (later.values().stream().allMatch(List::isEmpty)) {
			out.line(HEADER, "- no rows.");
			return;
		}
		later.forEach((service, lines) -> {
			if (!lines.isEmpty()) {
				out.section("### " + service);
				lines.forEach(line -> out.line(service, line));
			}
		});
	}

	/**
	 * What no lab service owns, under "Other rows", written only when there is one: the traffic rows of no lab service,
	 * with all their labels, then the state tables and the event sources {@link CallGraph} names no owner for, each as
	 * a sheet writes it, but with every row in place, since the last section is per service. {@code traffic_counts}
	 * reads the lab's four services only, and every table and source the gatherer reads has its owner (a test checks
	 * it), so a name missing from the graph costs a sheet its facts, never the rendering.
	 */
	private static void others(Out out, Dossier dossier) {
		Compared<List<TrafficRow>> traffic = dossier.traffic();
		List<Row> rows = trafficRows(traffic,
				row -> !(("server".equals(row.side()) || "client".equals(row.side()))
						&& CallGraph.SERVICES.contains(row.service())),
				row -> Arrays.asList(row.side(), row.service(), row.clientName(), row.method(), row.uri(), row.status(),
						row.outcome(), row.exception()));
		List<TableStates> tables = dossier.states()
			.stream()
			.filter(table -> CallGraph.ownerOfTable(table.table()).isEmpty())
			.toList();
		List<SourceEvents> sources = dossier.events()
			.stream()
			.filter(source -> CallGraph.ownerOfSource(source.source()).isEmpty())
			.toList();
		if (rows.isEmpty() && tables.isEmpty() && sources.isEmpty()) {
			return;
		}
		out.section("## Other rows");
		Sheet others = Sheet.others(out);
		counted("", traffic, rows,
				labels -> words(key("side", labels.get(0)), key("service", labels.get(1)), key("client", labels.get(2)),
						cell(labels.get(3)), cell(labels.get(4)), key("status", labels.get(5)),
						key("outcome", labels.get(6)), key("exception", labels.get(7))))
			.forEach(others::line);
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
		final List<String> later;

		private Sheet(String service, Out out, List<String> later) {
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

		void line(String line) {
			this.out.line(this.service, line);
		}

	}

	/**
	 * The text being written, numbered as {@link DossierRenderer} numbers it, with the owner of every numbered line;
	 * sections are separated by one blank line.
	 */
	private static final class Out {

		private final StringBuilder text = new StringBuilder();

		private final List<String> owners = new ArrayList<>();

		void line(String owner, String line) {
			this.owners.add(owner);
			this.text.append(DossierRenderer.linePrefix(this.owners.size())).append(line).append('\n');
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
