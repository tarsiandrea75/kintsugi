package dev.kintsugi.agent.summary;

import static dev.kintsugi.agent.summary.SummaryText.field;
import static dev.kintsugi.agent.summary.SummaryText.number;
import static dev.kintsugi.agent.summary.SummaryText.quote;
import static dev.kintsugi.agent.summary.SummaryText.time;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * One summarizer per tool, for an {@code OK} result (agent spec, "Summaries built in code"). Each returns the lines
 * after the header. Every "top N" sorts by its value, then by the time of the item's first occurrence (its position
 * when it has no time), then by its text, so no tie is left to the order of the output or of a hash map.
 */
final class Summarizers {

	static final int LOWEST_STOCK = 10;

	static final int SERIES = 5;

	static final int START_TIMES = 6;

	static final int LOG_MESSAGES = 5;

	static final int MESSAGE_CHARS = 200;

	static final int NEWEST_EVENTS = 3;

	static final int EVENT_TEXT_CHARS = 150;

	static final int TRACE_EVENTS = 10;

	static final int RUNBOOK_CHUNKS = 3;

	/**
	 * The characters of a runbook chunk's text a summary shows (T5c8; 300 before). The fourth live run cut chunks at
	 * 300 characters, before the text that told their causes apart; at 900 a typical chunk is shown whole or nearly.
	 */
	static final int RUNBOOK_TEXT_CHARS = 900;

	/**
	 * Room kept for the header, which the summarizer does not write: {@code toolCallId=<n> tool=search_runbooks
	 * outcome=<outcome> returned=<n> truncated=<bool>} is under 100 characters.
	 */
	static final int HEADER_ROOM = 120;

	/** The separator between the logger and the message in a Spring Boot console line. */
	static final String LOGGER_SEPARATOR = " : ";

	private static final Comparator<Instant> EARLIEST_FIRST = Comparator.nullsLast(Comparator.naturalOrder());

	private Summarizers() {
	}

	/** Every {@code status}/{@code count} pair; for stock, the number of SKUs and the 10 lowest {@code available}. */
	static List<String> statusSummary(JsonNode result) {
		List<JsonNode> items = items(result);
		List<String> lines = new ArrayList<>();
		if (items.stream().anyMatch(item -> item.has("sku"))) {
			lines.add("skus=" + items.size() + " lowest available:");
			List<Ranked> ranked = new ArrayList<>();
			for (int i = 0; i < items.size(); i++) {
				JsonNode item = items.get(i);
				ranked.add(new Ranked(item, item.path("available").asDouble(), null, i, item.path("sku").asString("")));
			}
			ranked.sort(Ranked.ASCENDING);
			ranked.stream()
				.limit(LOWEST_STOCK)
				.forEach(r -> lines.add("sku=" + field(r.item(), "sku", 100) + " available=" + number(r.item(), "available")));
		}
		else {
			items.forEach(item -> lines.add("status=" + field(item, "status", 100) + " count=" + number(item, "count")));
		}
		return lines;
	}

	/**
	 * At most 5 series, ranked by their max value: labels, number of points, first, last, min and max value with the
	 * time of the max, and the number of {@code NaN} points.
	 */
	static List<String> queryMetric(JsonNode result) {
		List<JsonNode> items = items(result);
		List<Series> series = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			series.add(Series.of(items.get(i), i));
		}
		series.sort(Comparator.comparing(Series::max, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(Series::firstTime, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(Series::labels)
			.thenComparingInt(Series::position));
		List<String> lines = new ArrayList<>();
		lines.add("series=" + items.size() + " shown=" + Math.min(SERIES, items.size()) + ", highest max first");
		series.stream().limit(SERIES).forEach(s -> lines.add(s.line()));
		return lines;
	}

	/** Per service, {@code restartsInWindow} and its 6 newest start times. */
	static List<String> serviceRestarts(JsonNode result) {
		List<String> lines = new ArrayList<>();
		for (JsonNode item : items(result)) {
			List<String> times = new ArrayList<>();
			item.path("startTimes").forEach(time -> times.add(time.asString()));
			List<String> newest = times.subList(Math.max(0, times.size() - START_TIMES), times.size());
			String earlier = times.size() > newest.size() ? " earlier=" + (times.size() - newest.size()) : "";
			lines.add("service=" + field(item, "service", 100) + " restartsInWindow=" + number(item, "restartsInWindow")
					+ " startTimes=" + newest.stream().map(time -> quote(time, 40)).toList() + earlier);
		}
		return lines;
	}

	/**
	 * The 5 most frequent distinct messages (the text after the logger separator), each at most 200 characters, with
	 * its count and newest timestamp.
	 */
	static List<String> searchLogs(JsonNode result) {
		Map<String, Message> messages = new LinkedHashMap<>();
		for (JsonNode item : items(result)) {
			String line = item.path("untrustedText").asString("");
			int separator = line.indexOf(LOGGER_SEPARATOR);
			String text = separator < 0 ? line : line.substring(separator + LOGGER_SEPARATOR.length());
			messages.computeIfAbsent(text, Message::new).add(item);
		}
		List<Message> ranked = new ArrayList<>(messages.values());
		ranked.sort(Comparator.comparingInt(Message::count)
			.reversed()
			.thenComparing(Message::first, EARLIEST_FIRST)
			.thenComparing(Message::text));
		List<String> lines = new ArrayList<>();
		lines.add("lines=" + items(result).size() + " distinctMessages=" + ranked.size() + ", most frequent first");
		ranked.stream()
			.limit(LOG_MESSAGES)
			.forEach(m -> lines.add("count=" + m.count() + " newest=" + quote(m.newestTimestamp(), 40) + " untrustedText="
					+ quote(m.text(), MESSAGE_CHARS)));
		return lines;
	}

	/** {@code service} and {@code count}. */
	static List<String> countLogs(JsonNode result) {
		return items(result).stream()
			.map(item -> "service=" + field(item, "service", 100) + " count=" + number(item, "count"))
			.toList();
	}

	/**
	 * Counts by {@code type} and {@code outcome}; min, median and max {@code durationMs}; the 3 newest events with
	 * {@code untrustedText} at most 150 characters.
	 */
	static List<String> recentEvents(JsonNode result) {
		List<JsonNode> items = items(result);
		List<String> lines = new ArrayList<>();
		lines.add("events=" + items.size());
		Map<String, Ranked> pairs = new LinkedHashMap<>();
		List<Long> durations = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			String pair = "type=" + field(item, "type", 100) + " outcome=" + field(item, "outcome", 100);
			Instant at = time(item, "occurredAt");
			Ranked previous = pairs.get(pair);
			Instant first = previous == null || EARLIEST_FIRST.compare(at, previous.time()) < 0 ? at : previous.time();
			pairs.put(pair, new Ranked(item, previous == null ? 1 : previous.value() + 1, first,
					previous == null ? i : previous.position(), pair));
			if (item.path("durationMs").isNumber()) {
				durations.add(item.path("durationMs").asLong());
			}
		}
		List<Ranked> counts = new ArrayList<>(pairs.values());
		counts.sort(Ranked.DESCENDING);
		counts.forEach(r -> lines.add(r.text() + " count=" + (long) r.value()));
		durations.sort(Comparator.naturalOrder());
		lines.add(durations.isEmpty() ? "durationMs: none recorded"
				: "durationMs min=" + durations.get(0) + " median=" + durations.get((durations.size() - 1) / 2) + " max="
						+ durations.get(durations.size() - 1) + " of " + durations.size() + " events");
		// Newest first; an event without a time sorts last; then by position, which is unique.
		List<Integer> newest = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			newest.add(i);
		}
		newest.sort(Comparator.comparing((Integer i) -> time(items.get(i), "occurredAt"),
				Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
			.thenComparing(Comparator.naturalOrder()));
		lines.add("newest " + Math.min(NEWEST_EVENTS, items.size()) + ":");
		newest.stream().limit(NEWEST_EVENTS).forEach(i -> lines.add(event(items.get(i), true)));
		return lines;
	}

	/** The order's {@code status}, {@code createdAt}, {@code updatedAt}, then at most 10 events in time order. */
	static List<String> orderTrace(JsonNode result) {
		List<String> lines = new ArrayList<>();
		List<JsonNode> events = new ArrayList<>();
		JsonNode order = null;
		for (JsonNode item : items(result)) {
			if ("order".equals(item.path("kind").asString("")) && order == null) {
				order = item;
			}
			else if ("event".equals(item.path("kind").asString(""))) {
				events.add(item);
			}
		}
		lines.add(order == null ? "order: no row"
				: "order id=" + field(order, "id", 40) + " status=" + field(order, "status", 100) + " createdAt="
						+ field(order, "createdAt", 40) + " updatedAt=" + field(order, "updatedAt", 40));
		lines.add("events=" + events.size() + " shown=" + Math.min(TRACE_EVENTS, events.size()) + ", oldest first");
		events.stream().limit(TRACE_EVENTS).forEach(event -> lines.add(event(event, false)));
		return lines;
	}

	/**
	 * The nearest chunks by score, at most 3, while they fit the summary's cap (the nearest always):
	 * {@code service}, {@code section}, {@code score} and the first 900 characters of text. {@code shown} counts the
	 * chunks the summary holds, so the model knows how many it did not see.
	 */
	static List<String> searchRunbooks(JsonNode result) {
		List<JsonNode> items = items(result);
		List<Ranked> ranked = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			ranked.add(new Ranked(item, item.path("score").asDouble(), null, i, item.path("untrustedText").asString("")));
		}
		ranked.sort(Ranked.DESCENDING);
		List<String> chunks = new ArrayList<>();
		for (Ranked r : ranked.subList(0, Math.min(RUNBOOK_CHUNKS, ranked.size()))) {
			chunks.add("service=" + field(r.item(), "service", 100) + " section=" + field(r.item(), "section", 120)
					+ " score=" + number(r.item(), "score") + " untrustedText="
					+ field(r.item(), "untrustedText", RUNBOOK_TEXT_CHARS));
		}
		String first = ", nearest first";
		int left = ToolSummaries.MAX_CHARS - HEADER_ROOM - ("chunks=" + items.size() + " shown=N" + first).length() - 1;
		int shown = 0;
		while (shown < chunks.size() && (shown == 0 || chunks.get(shown).length() + 1 <= left)) {
			left -= chunks.get(shown).length() + 1;
			shown++;
		}
		List<String> lines = new ArrayList<>();
		lines.add("chunks=" + items.size() + " shown=" + shown + first);
		lines.addAll(chunks.subList(0, shown));
		return lines;
	}

	private static String event(JsonNode event, boolean withOrder) {
		StringBuilder line = new StringBuilder();
		if (event.has("source")) {
			line.append("source=").append(field(event, "source", 40)).append(' ');
		}
		line.append("occurredAt=").append(field(event, "occurredAt", 40));
		if (withOrder) {
			line.append(" orderId=").append(field(event, "orderId", 40));
		}
		line.append(" type=")
			.append(field(event, "type", 100))
			.append(" outcome=")
			.append(field(event, "outcome", 100))
			.append(" durationMs=")
			.append(number(event, "durationMs"));
		if (event.has("untrustedText")) {
			line.append(" untrustedText=").append(field(event, "untrustedText", EVENT_TEXT_CHARS));
		}
		return line.toString();
	}

	private static List<JsonNode> items(JsonNode result) {
		List<JsonNode> items = new ArrayList<>();
		result.path("items").forEach(items::add);
		return items;
	}

	/**
	 * An item ranked by {@code value}, then by its first time ({@code null} sorts last) or, without times, its
	 * position, then by its text.
	 */
	private record Ranked(JsonNode item, double value, Instant time, int position, String text) {

		static final Comparator<Ranked> TIES = Comparator.comparing(Ranked::time, EARLIEST_FIRST)
			.thenComparingInt(Ranked::position)
			.thenComparing(Ranked::text);

		static final Comparator<Ranked> ASCENDING = Comparator.comparingDouble(Ranked::value).thenComparing(TIES);

		static final Comparator<Ranked> DESCENDING = Comparator.comparingDouble(Ranked::value)
			.reversed()
			.thenComparing(TIES);

	}

	/** One distinct log message: how often it occurred, and its earliest and newest times. */
	private static final class Message {

		private final String text;

		private int count;

		private Instant first;

		private Instant newest;

		private String newestTimestamp = "";

		Message(String text) {
			this.text = text;
		}

		void add(JsonNode line) {
			this.count++;
			Instant at = time(line, "timestamp");
			if (at != null && (this.first == null || at.isBefore(this.first))) {
				this.first = at;
			}
			if (at != null && (this.newest == null || at.isAfter(this.newest))) {
				this.newest = at;
				this.newestTimestamp = line.path("timestamp").asString();
			}
		}

		String text() {
			return this.text;
		}

		int count() {
			return this.count;
		}

		Instant first() {
			return this.first;
		}

		String newestTimestamp() {
			return this.newestTimestamp;
		}

	}

	/** One metric series and the figures its line shows. */
	private record Series(JsonNode item, int position, String labels, Double max, Long firstTime, String line) {

		static Series of(JsonNode item, int position) {
			String labels = SummaryText.labels(item.get("labels"));
			JsonNode points = item.path("points");
			int nan = 0;
			JsonNode minPoint = null;
			JsonNode maxPoint = null;
			Double min = null;
			Double max = null;
			for (JsonNode point : points) {
				double value = value(point.path(1).asString(""));
				if (Double.isNaN(value)) {
					nan++;
					continue;
				}
				if (min == null || value < min) {
					min = value;
					minPoint = point;
				}
				if (max == null || value > max) {
					max = value;
					maxPoint = point;
				}
			}
			Long firstTime = points.isEmpty() ? null : points.path(0).path(0).asLong();
			StringBuilder line = new StringBuilder("labels=").append(labels).append(" points=").append(points.size());
			if (!points.isEmpty()) {
				line.append(" first=")
					.append(quote(points.path(0).path(1).asString(""), 40))
					.append(" last=")
					.append(quote(points.path(points.size() - 1).path(1).asString(""), 40));
			}
			line.append(" min=").append(minPoint == null ? "-" : quote(minPoint.path(1).asString(""), 40));
			line.append(" max=").append(maxPoint == null ? "-" : quote(maxPoint.path(1).asString(""), 40));
			if (maxPoint != null) {
				long at = maxPoint.path(0).asLong();
				line.append(" maxAt=").append(at).append(" (").append(Instant.ofEpochSecond(at)).append(')');
			}
			line.append(" nan=").append(nan);
			if (item.hasNonNull("truncated")) {
				line.append(" seriesTruncated=").append(field(item, "truncated", 80));
			}
			return new Series(item, position, labels, max, firstTime, line.toString());
		}

		/** A Prometheus sample value: a decimal, {@code NaN}, {@code +Inf} or {@code -Inf}. */
		private static double value(String text) {
			return switch (text) {
				case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
				case "-Inf" -> Double.NEGATIVE_INFINITY;
				default -> {
					try {
						yield Double.parseDouble(text);
					}
					catch (NumberFormatException unreadable) {
						yield Double.NaN;
					}
				}
			};
		}

	}

}
