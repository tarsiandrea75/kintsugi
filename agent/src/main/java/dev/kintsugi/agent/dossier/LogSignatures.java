package dev.kintsugi.agent.dossier;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import dev.kintsugi.agent.dossier.Dossier.LogSignature;

/**
 * Groups {@code search_logs} lines by signature: the message after the logger, with UUIDs and hex ids replaced by
 * {@code <id>} and then every number by {@code #}. It is the same rule the {@code search_logs} summary uses
 * ({@code Summarizers.template}, agent spec "Summaries built in code"), so the dossier and the explorer's summaries
 * group lines alike; it is repeated here because that one is private to its package.
 */
final class LogSignatures {

	private static final String LOGGER_SEPARATOR = " : ";

	private static final Pattern UUID = Pattern
		.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

	private static final Pattern HEX_ID = Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}\\b");

	private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)*");

	private static final Comparator<Group> ORDER = Comparator.comparingInt(Group::count)
		.reversed()
		.thenComparing(Group::newest, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(Group::signature)
		.thenComparing(Group::message);

	private LogSignatures() {
	}

	/**
	 * The signatures of {@code lines}, most frequent first, then newest first, then by text. Each shows its newest
	 * message (the first one seen while no line has a readable timestamp).
	 */
	static List<LogSignature> of(JsonNode lines) {
		Map<String, Group> groups = new LinkedHashMap<>();
		for (JsonNode line : lines) {
			String text = line.path("untrustedText").asString("");
			int separator = text.indexOf(LOGGER_SEPARATOR);
			String message = separator < 0 ? text : text.substring(separator + LOGGER_SEPARATOR.length());
			groups.computeIfAbsent(signature(message), Group::new).add(message, timestamp(line));
		}
		return groups.values()
			.stream()
			.sorted(ORDER)
			.map(group -> new LogSignature(group.count(), group.newest(), new UntrustedText(group.message())))
			.toList();
	}

	static String signature(String message) {
		String text = UUID.matcher(message).replaceAll("<id>");
		text = HEX_ID.matcher(text).replaceAll("<id>");
		return NUMBER.matcher(text).replaceAll("#");
	}

	private static Instant timestamp(JsonNode line) {
		JsonNode value = line.get("timestamp");
		if (value == null || !value.isString()) {
			return null;
		}
		try {
			return Instant.parse(value.asString());
		}
		catch (DateTimeParseException unreadable) {
			return null;
		}
	}

	private static final class Group {

		private final String signature;

		private String message;

		private Instant newest;

		private int count;

		Group(String signature) {
			this.signature = signature;
		}

		void add(String text, Instant at) {
			this.count++;
			if (this.message == null) {
				this.message = text;
			}
			if (at != null && (this.newest == null || at.isAfter(this.newest))) {
				this.newest = at;
				this.message = text;
			}
		}

		String signature() {
			return this.signature;
		}

		String message() {
			return this.message;
		}

		Instant newest() {
			return this.newest;
		}

		int count() {
			return this.count;
		}

	}

}
