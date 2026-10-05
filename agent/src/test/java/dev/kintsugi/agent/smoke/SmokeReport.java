package dev.kintsugi.agent.smoke;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Scores a smoke-test run against the adoption bar of {@code odd/tasks/model-smoke-test.md} and
 * renders it as Markdown. Pure logic: no model and no Spring involved.
 */
final class SmokeReport {

	enum Verdict {

		/** At least 80% of positives valid and at least one negative without a call. */
		ADOPT,

		/** Neither ADOPT nor PLAN_B. */
		REJECT,

		/** Under 50% of positives valid ({@code docs/vision.md}, plan B trigger). */
		PLAN_B

	}

	static final double ADOPT_THRESHOLD = 0.8;

	static final double PLAN_B_THRESHOLD = 0.5;

	static final int MIN_NEGATIVES_PASSED = 1;

	private final String model;

	private final List<PromptResult> results;

	SmokeReport(String model, List<PromptResult> results) {
		this.model = model;
		this.results = List.copyOf(results);
	}

	/** The report file name for a model, safe for any file system. */
	static String fileName(String model) {
		return model.replaceAll("[^A-Za-z0-9._-]", "_") + ".md";
	}

	Verdict verdict() {
		double valid = validPositiveRate();
		if (valid < PLAN_B_THRESHOLD) {
			return Verdict.PLAN_B;
		}
		if (valid >= ADOPT_THRESHOLD && negativesPassed() >= MIN_NEGATIVES_PASSED) {
			return Verdict.ADOPT;
		}
		return Verdict.REJECT;
	}

	double validPositiveRate() {
		return rate(positives().stream().filter(PromptResult::passed).count(), positives().size());
	}

	double firstAttemptValidRate() {
		return rate(positives().stream().filter(PromptResult::firstCallValid).count(), positives().size());
	}

	long negativesPassed() {
		return negatives().stream().filter(PromptResult::passed).count();
	}

	Duration medianLatency() {
		List<Duration> sorted = results.stream().map(PromptResult::latency).sorted().toList();
		if (sorted.isEmpty()) {
			return Duration.ZERO;
		}
		int mid = sorted.size() / 2;
		if (sorted.size() % 2 == 1) {
			return sorted.get(mid);
		}
		return sorted.get(mid - 1).plus(sorted.get(mid)).dividedBy(2);
	}

	Duration maxLatency() {
		return results.stream().map(PromptResult::latency).max(Duration::compareTo).orElse(Duration.ZERO);
	}

	String toMarkdown() {
		StringBuilder md = new StringBuilder();
		md.append("# Tool-calling smoke test: ").append(model).append("\n\n");
		md.append("**Verdict: ").append(verdict()).append("**\n\n");
		md.append("| Metric | Value |\n|---|---|\n");
		md.append("| Valid positives (gating, adopt at >= 80%) | ")
			.append(count(positives().stream().filter(PromptResult::passed).count(), positives().size()))
			.append(" |\n");
		md.append("| Negatives without a call (gating, >= ")
			.append(MIN_NEGATIVES_PASSED)
			.append(") | ")
			.append(negativesPassed())
			.append(" of ")
			.append(negatives().size())
			.append(" |\n");
		md.append("| First-attempt argument validity | ")
			.append(count(positives().stream().filter(PromptResult::firstCallValid).count(), positives().size()))
			.append(" |\n");
		md.append("| Median latency per prompt | ").append(seconds(medianLatency())).append(" |\n");
		md.append("| Max latency per prompt | ").append(seconds(maxLatency())).append(" |\n\n");
		md.append("| ID | Kind | Passed | Calls | Expected in answer | Latency | Prompt | Answer |\n");
		md.append("|---|---|---|---|---|---|---|---|\n");
		for (PromptResult r : results) {
			md.append("| ")
				.append(r.prompt().id())
				.append(" | ")
				.append(r.prompt().positive() ? "positive" : "negative")
				.append(" | ")
				.append(r.passed() ? "yes" : "no")
				.append(" | ")
				.append(calls(r.calls()))
				.append(" | ")
				.append(r.containsExpected().map(c -> c ? "yes" : "no").orElse("n/a"))
				.append(" | ")
				.append(seconds(r.latency()))
				.append(" | ")
				.append(cell(r.prompt().prompt()))
				.append(" | ")
				.append(cell(r.answer()))
				.append(" |\n");
		}
		return md.toString();
	}

	private List<PromptResult> positives() {
		return results.stream().filter(r -> r.prompt().positive()).toList();
	}

	private List<PromptResult> negatives() {
		return results.stream().filter(r -> !r.prompt().positive()).toList();
	}

	private static double rate(long part, int total) {
		return total == 0 ? 0 : (double) part / total;
	}

	private static String count(long part, int total) {
		return "%d of %d (%.1f%%)".formatted(part, total, 100 * rate(part, total));
	}

	private static String seconds(Duration d) {
		return "%.1f s".formatted(d.toMillis() / 1000.0);
	}

	private static String calls(List<ToolCall> calls) {
		if (calls.isEmpty()) {
			return "none";
		}
		return calls.stream()
			.map(c -> "`" + cell(c.arguments()) + "`" + (c.error() ? " (error)" : ""))
			.collect(Collectors.joining("<br>"));
	}

	/** Keeps a value inside one Markdown table cell. */
	private static String cell(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ").strip();
	}

}
