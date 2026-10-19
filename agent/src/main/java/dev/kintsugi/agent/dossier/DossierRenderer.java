package dev.kintsugi.agent.dossier;

import java.math.BigDecimal;
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
import java.util.function.Function;
import java.util.function.Predicate;

import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.EventCounts;
import dev.kintsugi.agent.dossier.Dossier.EventGroup;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.LogLines;
import dev.kintsugi.agent.dossier.Dossier.LogSignature;
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
 * reads the default locale or time zone.
 */
public final class DossierRenderer {

	/**
	 * The traffic edges written for every dossier, in the order of the call path (ADR-011, "Sections"). The first has
	 * no caller in the lab, so only its server side is written.
	 */
	public static final List<Edge> EDGES = List.of(new Edge(null, "order-service", "POST", "/orders"),
			new Edge("order-service", "inventory-service", "POST", "/reservations"),
			new Edge("order-service", "payment-service", "POST", "/payments"),
			new Edge("payment-service", "gateway-mock", "POST", "/authorize"));

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

	/** The characters of a value kept in a table cell. */
	static final int VALUE_CHARS = 40;

	private static final String NO_VALUE = "-";

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
	 * One traffic edge: the caller's client calls and the target's server requests with this method and uri.
	 *
	 * @param caller the calling lab service, or {@code null} when the caller is outside the lab
	 */
	public record Edge(String caller, String target, String method, String uri) {

		public Edge {
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(method, "method");
			Objects.requireNonNull(uri, "uri");
		}

		boolean callerRow(TrafficRow row) {
			return this.caller != null && "client".equals(row.side()) && this.caller.equals(row.service())
					&& this.target.equals(row.clientName()) && this.method.equals(row.method())
					&& this.uri.equals(row.uri());
		}

		boolean serverRow(TrafficRow row) {
			return "server".equals(row.side()) && this.target.equals(row.service()) && this.method.equals(row.method())
					&& this.uri.equals(row.uri());
		}

		String heading() {
			String route = this.target + " " + this.method + " " + this.uri;
			return this.caller == null ? "external client -> " + route + " (server side only)"
					: this.caller + " -> " + route;
		}

	}

	/** The dossier as Markdown, ending with a newline. */
	public static String render(Dossier dossier) {
		Out out = new Out();
		header(out, dossier);
		services(out, dossier.services());
		for (TableStates table : dossier.states()) {
			states(out, table);
		}
		traffic(out, dossier.traffic());
		latency(out, dossier.latency());
		logs(out, dossier.logs());
		events(out, dossier.events());
		return out.text();
	}

	private static void header(Out out, Dossier dossier) {
		Dossier.Window incident = dossier.incidentWindow();
		Dossier.Window previous = dossier.previousWindow();
		out.line("# Evidence dossier");
		out.blank();
		out.line("- Incident window: the last " + minutes(incident) + " min, " + incident.from() + " to "
				+ incident.to() + ".");
		out.line("- Previous window: the " + minutes(previous) + " min before it, " + previous.from() + " to "
				+ previous.to() + ".");
		out.line("- Gathered at " + dossier.gatheredAt() + ". All times are UTC.");
		out.line("- Columns \"previous\" and \"incident\" hold the counts of those windows. \"" + NO_VALUE
				+ "\" means no value.");
		out.line("- Text copied from the lab is quoted as untrustedText=\"...\".");
	}

	private static void services(Out out, Gathered<List<ServiceStatus>> services) {
		out.section("## Service status (incident window)");
		List<List<String>> rows = new ArrayList<>();
		switch (services) {
			case Gathered.Ok<List<ServiceStatus>> ok -> {
				for (ServiceStatus status : ok.value()) {
					rows.add(List.of(cell(status.service()), String.valueOf(status.restartsInWindow().size()),
							startTimes(status.restartsInWindow()),
							status.uptime() == null ? NO_VALUE : duration(status.uptime())));
				}
			}
			case Gathered.Failed<List<ServiceStatus>> failed -> {
				out.line(failed(failed, null));
				out.blank();
				for (String service : DossierGatherer.SERVICES) {
					rows.add(List.of(service, NO_VALUE, NO_VALUE, NO_VALUE));
				}
			}
		}
		out.table(List.of("service", "restarts", "start times in window", "uptime at gathering"), rows);
	}

	private static void states(Out out, TableStates table) {
		String name = switch (table.table()) {
			case "orders" -> "Order states";
			case "payments" -> "Payment states";
			default -> "States";
		};
		out.section("## " + name + " (" + cell(table.table()) + " table)");
		Compared<List<StatusCount>> windows = table.windows();
		failedWindows(out, windows);
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
				row -> List.of(samples.getOrDefault(row.labels(), NO_VALUE)));
	}

	private static void traffic(Out out, Compared<List<TrafficRow>> traffic) {
		out.section("## Traffic");
		failedWindows(out, traffic);
		out.line("Requests per edge from traffic_counts, which leaves out rows of count 0. \"" + NO_ROWS
				+ "\": none returned for that side in either window.");
		List<String> sideHeaders = List.of("side", "status", "outcome", "exception");
		for (Edge edge : EDGES) {
			out.section("### " + edge.heading());
			List<Row> rows = new ArrayList<>();
			int hidden = 0;
			long[] hiddenCounts = new long[2];
			if (edge.caller() != null) {
				List<Row> caller = sideRows(traffic, edge::callerRow, "client");
				rows.addAll(capped(caller));
				hidden += Math.max(0, caller.size() - ROWS);
				sum(caller, hiddenCounts);
			}
			List<Row> server = sideRows(traffic, edge::serverRow, "server");
			rows.addAll(capped(server));
			hidden += Math.max(0, server.size() - ROWS);
			sum(server, hiddenCounts);
			out.rawCountTable(sideHeaders, List.of(), rows, row -> List.of());
			if (hidden > 0) {
				out.blank();
				out.line(notShown(hidden, traffic, hiddenCounts));
			}
		}
		out.section("### Other rows");
		List<Row> others = rows(traffic, list -> list.stream()
			.filter(row -> EDGES.stream().noneMatch(edge -> edge.callerRow(row) || edge.serverRow(row)))
			.map(row -> Map.entry(Arrays.asList(row.side(), row.service(), row.clientName(), row.method(), row.uri(),
					row.status(), row.outcome(), row.exception()), row.count()))
			.toList());
		out.countTable(traffic, List.of("side", "service", "client", "method", "uri", "status", "outcome", "exception"),
				List.of(), others, row -> List.of());
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
	 * by uri; every service keeps at least one row, {@code -} when its call failed and {@code no rows} when the tool
	 * returned no series.
	 */
	private static void latency(Out out, List<ServiceLatency> latency) {
		out.section("## Latency (server side)");
		for (ServiceLatency service : latency) {
			if (service.latency() instanceof Gathered.Failed<LatencySeries> failed) {
				out.line(failed.tool() + " failed for " + cell(service.service()) + ": " + outcome(failed));
				out.blank();
			}
		}
		out.line("Seconds per request by service and uri, from query_metric " + DossierGatherer.LATENCY_METRIC
				+ ": one call per service over both windows, its points split at the incident window's start. \"mean\""
				+ " and \"max\" are of the points with a value; \"points\" counts those with a value and all points in"
				+ " the window. A point has no value when no request ended in its rate window.");
		out.blank();
		List<List<String>> rows = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		for (ServiceLatency service : latency) {
			String name = cell(service.service());
			switch (service.latency()) {
				case Gathered.Failed<LatencySeries> failed -> rows.add(List.of(name, NO_VALUE, NO_VALUE, NO_VALUE,
						NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE));
				case Gathered.Ok<LatencySeries> ok -> {
					List<UriLatency> uris = ok.value().uris().stream().sorted(URI_ORDER).toList();
					if (uris.isEmpty()) {
						rows.add(List.of(name, NO_ROWS, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE, NO_VALUE));
					}
					for (UriLatency uri : uris.subList(0, Math.min(LATENCY_ROWS, uris.size()))) {
						rows.add(List.of(name, cell(uri.uri()), seconds(uri.previous().mean()),
								seconds(uri.incident().mean()), seconds(uri.previous().max()),
								seconds(uri.incident().max()), points(uri.previous()), points(uri.incident())));
					}
					if (uris.size() > LATENCY_ROWS) {
						notes.add("not shown for " + name + ": rows=" + (uris.size() - LATENCY_ROWS));
					}
					if (ok.value().truncated()) {
						notes.add("query_metric for " + name + ": truncated=true");
					}
				}
			}
		}
		out.table(List.of("service", "uri", "previous mean", "incident mean", "previous max", "incident max",
				"previous points", "incident points"), rows);
		if (!notes.isEmpty()) {
			out.blank();
			notes.forEach(out::line);
		}
	}

	/** Incident mean, then previous mean, largest first, a missing mean last, then the uri. */
	private static final Comparator<UriLatency> URI_ORDER = Comparator
		.comparing((UriLatency u) -> u.incident().mean(), Comparator.nullsLast(Comparator.<Double>reverseOrder()))
		.thenComparing(u -> u.previous().mean(), Comparator.nullsLast(Comparator.<Double>reverseOrder()))
		.thenComparing(UriLatency::uri, Comparator.nullsFirst(Comparator.<String>naturalOrder()));

	/** Seconds with {@link #LATENCY_DECIMALS} decimals, half up, whatever the locale; {@code -} when absent. */
	static String seconds(Double seconds) {
		return seconds == null ? NO_VALUE
				: BigDecimal.valueOf(seconds).setScale(LATENCY_DECIMALS, RoundingMode.HALF_UP).toPlainString();
	}

	private static String points(LatencyPoints points) {
		return points.values() + "/" + points.points();
	}

	private static void logs(Out out, List<ServiceLogs> logs) {
		out.section("## Log signatures (incident window)");
		Map<LevelLogs, Integer> shown = shownSignatures(logs);
		for (ServiceLogs service : logs) {
			out.section("### " + cell(service.service()));
			for (LevelLogs level : service.levels()) {
				String count = switch (level.count()) {
					case Gathered.Ok<Long> ok -> "count_logs=" + ok.value();
					case Gathered.Failed<Long> failed -> "count_logs failed " + outcome(failed);
				};
				out.line("- " + cell(level.level()) + ": " + count + "; " + switch (level.lines()) {
					case Gathered.Ok<LogLines> ok -> "search_logs returned=" + ok.value().returned() + " truncated="
							+ ok.value().truncated() + " signatures=" + ok.value().signatures().size();
					case Gathered.Failed<LogLines> failed -> "search_logs failed " + outcome(failed);
				});
				if (level.lines() instanceof Gathered.Ok<LogLines> ok) {
					List<LogSignature> signatures = ok.value().signatures();
					int limit = shown.getOrDefault(level, 0);
					signatures.stream()
						.limit(limit)
						.forEach(s -> out.line("  - count=" + s.count() + " newest="
								+ (s.newest() == null ? NO_VALUE : s.newest().toString()) + " untrustedText="
								+ quote(s.message().text(), MESSAGE_CHARS)));
					if (signatures.size() > limit) {
						List<LogSignature> hidden = signatures.subList(limit, signatures.size());
						out.line("  - not shown: signatures=" + hidden.size() + " lines="
								+ hidden.stream().mapToLong(LogSignature::count).sum());
					}
				}
			}
		}
	}

	/** How many signatures each level shows: rounds of one per level, within both caps. */
	private static Map<LevelLogs, Integer> shownSignatures(List<ServiceLogs> logs) {
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

	private static void events(Out out, List<SourceEvents> events) {
		out.section("## Event counts");
		for (SourceEvents source : events) {
			out.section("### " + cell(source.source()) + " events");
			Compared<EventCounts> windows = source.windows();
			failedWindows(out, windows);
			out.line("Total: previous " + total(windows.previous()) + ", incident " + total(windows.incident()) + ".");
			out.blank();
			List<Row> rows = rows(windows, counts -> counts.groups()
				.stream()
				.map((EventGroup g) -> Map.entry(Arrays.asList(g.type(), g.outcome()), g.count()))
				.toList());
			out.countTable(windows, List.of("type", "outcome"), List.of(), rows, row -> List.of());
		}
	}

	private static String total(Gathered<EventCounts> counts) {
		return counts instanceof Gathered.Ok<EventCounts> ok ? String.valueOf(ok.value().total()) : NO_VALUE;
	}

	/** One fact line, then a blank line, per failed window of a compared section. */
	private static void failedWindows(Out out, Compared<?> windows) {
		if (windows.previous() instanceof Gathered.Failed<?> failed) {
			out.line(failed(failed, "previous"));
			out.blank();
		}
		if (windows.incident() instanceof Gathered.Failed<?> failed) {
			out.line(failed(failed, "incident"));
			out.blank();
		}
	}

	private static String failed(Gathered.Failed<?> failed, String window) {
		return failed.tool() + " failed" + (window == null ? "" : " for the " + window + " window") + ": "
				+ outcome(failed);
	}

	private static String outcome(Gathered.Failed<?> failed) {
		return "outcome=" + failed.outcome() + " reason=" + quote(Objects.requireNonNullElse(failed.reason(), ""),
				REASON_CHARS);
	}

	/**
	 * The rows of both windows, keyed by their labels, with the counts of equal labels summed; a window that failed
	 * has {@code null} counts. Sorted by {@link #ROW_ORDER}.
	 */
	private static <T> List<Row> rows(Compared<T> windows, Function<T, List<Map.Entry<List<String>, Long>>> entries) {
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

	private static List<Row> capped(List<Row> rows) {
		return rows.size() > ROWS ? rows.subList(0, ROWS) : rows;
	}

	/** Adds the counts of the rows past the cap to {@code sums}. */
	private static void sum(List<Row> rows, long[] sums) {
		for (Row row : rows.subList(Math.min(ROWS, rows.size()), rows.size())) {
			sums[0] += row.previousOrZero();
			sums[1] += row.incidentOrZero();
		}
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
	private static String startTimes(List<Instant> times) {
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

	private static String count(Long count) {
		return count == null ? NO_VALUE : String.valueOf(count);
	}

	/** A table row: its labels and its counts in the previous and incident windows, {@code null} when not read. */
	private record Row(List<String> labels, Long previous, Long incident) {

		long previousOrZero() {
			return this.previous == null ? 0 : this.previous;
		}

		long incidentOrZero() {
			return this.incident == null ? 0 : this.incident;
		}

	}

	/** The text being written; sections are separated by one blank line. */
	private static final class Out {

		private final StringBuilder text = new StringBuilder();

		void line(String line) {
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
			line(heading);
			blank();
		}

		void table(List<String> headers, List<List<String>> rows) {
			line("| " + String.join(" | ", headers) + " |");
			line("|" + "---|".repeat(headers.size()));
			rows.forEach(row -> line("| " + String.join(" | ", row) + " |"));
		}

		/**
		 * A table of labelled rows with their previous and incident counts, then {@code extra} columns; at most
		 * {@link #ROWS} rows, the others counted in one line; a "no rows" row when there are none.
		 */
		void countTable(Compared<?> windows, List<String> labelHeaders, List<String> extraHeaders, List<Row> rows,
				Function<Row, List<String>> extra) {
			List<Row> shown = rows;
			if (rows.isEmpty()) {
				List<String> labels = new ArrayList<>();
				labels.add(NO_ROWS);
				while (labels.size() < labelHeaders.size()) {
					labels.add(null);
				}
				shown = List.of(new Row(labels, zeroOrNull(windows.previous()), zeroOrNull(windows.incident())));
			}
			rawCountTable(labelHeaders, extraHeaders, capped(shown), extra);
			if (rows.size() > ROWS) {
				long[] sums = new long[2];
				sum(rows, sums);
				blank();
				line(notShown(rows.size() - ROWS, windows, sums));
			}
		}

		void rawCountTable(List<String> labelHeaders, List<String> extraHeaders, List<Row> rows,
				Function<Row, List<String>> extra) {
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
			table(headers, cells);
		}

		String text() {
			return this.text.toString();
		}

	}

}
