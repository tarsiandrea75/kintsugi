package dev.kintsugi.agent.loop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.Change;
import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.dossier.LineFact;
import dev.kintsugi.agent.dossier.Section;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.lab.ReplayLabTools;
import dev.kintsugi.agent.loop.ReplayCases.Recording;
import dev.kintsugi.agent.policy.Policy;
import dev.kintsugi.agent.policy.PolicyTally;

/**
 * Re-checks the verification level of answers a replay run already stored (verification-levels T6; ADR-028), with no
 * model call: each decision incident's dossier is gathered again from its recording, as {@link ReplayDriver} gathers
 * it, and today's {@link ReportCheck} runs on the stored {@code REPORT} answer with the re-rendered text, its line facts
 * and the topology, as the loop does with the tables and the plain report.
 *
 * <p>
 * Two guards make the level the one the run would have stored: the dossier message must equal the stored
 * {@code DOSSIER} step's byte for byte, so the model read the text that is re-checked; and the re-check must accept the
 * answer exactly when the run stored a report, verifying the same evidence items, so it reads the lines the run read.
 * An incident that fails either is {@link Refused}, and a run with any refused incident is excluded whole: no
 * {@code summary.tsv} is written for it. Only the {@code dev} split is read; a row of any other split refuses the run
 * before anything is written, so no holdout is read.
 *
 * <p>
 * A run's outputs: {@code summary.tsv}, the input's rows unchanged but {@code level} (added last when the input
 * predates the column), so the scorer reads it as it reads a replay; {@code levels-detail.tsv}, one row per stored
 * evidence item with the cited line's owner, section and change and the sample's log-only flag; and
 * {@code recheck.txt}, the guards' outcome per incident.
 */
final class LevelRecheck {

	/** The columns of {@code levels-detail.tsv}: the sample's, then the evidence item's. */
	static final List<String> DETAIL_HEADER = List.of("fault", "recording", "sample", "truth", "reported", "verdict",
			"serviceCorrect", "level", "failedChecks", "logOnly", "item", "toolCallId", "line", "owner", "section",
			"changed", "causeOwned");

	/** The only split this re-check reads (the plan's rule): never a holdout. */
	static final String SPLIT = "dev";

	private static final String LEVEL = "level";

	private static final String NONE = "-";

	private static final JsonMapper JSON = JsonMapper.shared();

	/** The dossier's third line, which states the gathering's instant, the clock a replay's gatherer read. */
	private static final Pattern GATHERED = Pattern.compile("^D3 - Gathered at (\\S+)\\. All times are UTC\\.$",
			Pattern.MULTILINE);

	private final JsonNode toolsList;

	private final Topology topology;

	/**
	 * @param toolsList the recorded tool listing, as {@link ReplayDriver} serves it
	 * @param topology the system the run replayed, whose line facts and call paths the checks read
	 */
	LevelRecheck(Path toolsList, Topology topology) {
		this.toolsList = JSON.readTree(toolsList.toFile());
		this.topology = Objects.requireNonNull(topology);
	}

	/**
	 * Re-checks one stored decision incident against its recording.
	 * @param incident the incident as the replay wrote it: a {@code DOSSIER} step, its {@code REPORT} steps and, when
	 * the run stored one, its report
	 * @param recording the recording the run replayed
	 * @return the level today's checks give the stored answer, {@code -} when the run stored no report
	 * @throws Refused when the dossier does not re-render byte for byte, or the re-check does not verify what the run
	 * stored
	 */
	Rechecked recheck(JsonNode incident, JsonNode recording) {
		JsonNode dossierStep = null;
		JsonNode reportStep = null;
		for (JsonNode step : incident.path("steps")) {
			String kind = step.path("kind").asString("");
			switch (kind) {
				case "DOSSIER" -> dossierStep = step;
				case "REPORT" -> reportStep = step;
				default -> throw new Refused("step " + step.path("stepNo").asString("?") + " is " + kind
						+ ": a decision incident holds only its DOSSIER and REPORT steps");
			}
		}
		if (dossierStep == null || !dossierStep.path("summary").isString()) {
			throw new Refused("no DOSSIER step with its message");
		}
		String stored = dossierStep.path("summary").asString();
		DossierRenderer.Indexed dossier = DossierRenderer.indexed(gather(recording, gatheredAt(stored)), this.topology);
		String message = Prompts.dossier(dossier.text());
		if (!message.equals(stored)) {
			throw new Refused("the dossier differs " + firstDifference(stored, message));
		}

		JsonNode report = incident.path("report");
		boolean hasReport = report.isObject();
		if (reportStep == null) {
			if (hasReport) {
				throw new Refused("a stored report with no REPORT step");
			}
			return Rechecked.NONE;
		}
		// The last answer is the one the run accepted, or the one whose rejection ended it.
		JsonNode answer = Answers.json(reportStep.path("modelOutput").asString(null));
		ReportCheck.Result checked = ReportCheck.check(answer, Map.of(), AgentMode.DECISION, dossier.text(),
				dossier.facts(), this.topology);
		boolean accepted = checked.errors().isEmpty();
		if (accepted != hasReport) {
			throw new Refused(accepted ? "the re-check accepts an answer the run rejected"
					: "the re-check rejects the stored answer: " + checked.errors());
		}
		if (!hasReport) {
			return Rechecked.NONE;
		}
		List<JsonNode> storedEvidence = report.path("evidence").valueStream().toList();
		if (!storedEvidence.equals(checked.evidence())) {
			throw new Refused("the evidence differs: stored " + storedEvidence + ", re-checked " + checked.evidence());
		}

		String cause = report.path("rootCauseService").asString("");
		List<Item> items = checked.evidence().stream().map(item -> item(item, dossier.facts(), cause)).toList();
		List<Item> causeOwned = items.stream().filter(Item::causeOwned).toList();
		boolean logOnly = !causeOwned.isEmpty() && causeOwned.stream()
			.allMatch(item -> item.section().equals(name(Section.LOGS)) && item.changed().equals(Change.NOT_COMPARED.name()));
		Verification verification = checked.verification();
		List<String> failed = verification.checks()
			.stream()
			.filter(check -> Boolean.FALSE.equals(check.pass()))
			.map(Verification.Check::name)
			.toList();
		return new Rechecked(Verification.label(verification.level()), failed, items, logOnly);
	}

	/**
	 * Re-checks every sample of the replay run in {@code in} and writes the outputs to {@code out}.
	 * @param in a replay run's directory: its {@code summary.tsv} and one incident file per row
	 * @param recordings the replay directory the run read: {@code recordings.tsv} and the files it names
	 * @throws IllegalArgumentException before anything is written, for a row outside the {@code dev} split, a row
	 * other than a decision sample with the tables and the plain report, or a row whose recording is not in the
	 * manifest
	 */
	Outcome run(Path in, Path recordings, Path out) {
		Path summary = in.resolve("summary.tsv");
		List<String> lines = read(summary);
		List<String> header = Arrays.asList(lines.get(0).split("\t", -1));
		List<Map<String, String>> rows = new ArrayList<>();
		for (int i = 1; i < lines.size(); i++) {
			rows.add(row(header, lines.get(i), summary, i + 1));
		}
		Map<String, Recording> cases = new LinkedHashMap<>();
		List<String> faults = rows.stream().map(row -> row.get("fault")).distinct().toList();
		ReplayCases.read(recordings).decisionCases(SPLIT, faults).forEach(recording -> cases.put(recording.stem(), recording));
		for (Map<String, String> row : rows) {
			if (!cases.containsKey(row.get("recording"))) {
				throw new IllegalArgumentException(
						"the recording " + row.get("recording") + " is no dev case of " + recordings.resolve("recordings.tsv"));
			}
		}

		List<String> outcomes = new ArrayList<>();
		List<String> refused = new ArrayList<>();
		List<String> summaryRows = new ArrayList<>();
		List<String> detailRows = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, String> row = rows.get(i);
			String file = String.join("-", row.get("fault"), row.get("mode"), row.get("recording"), row.get("sample"))
					+ ".json";
			try {
				JsonNode incident = JSON.readTree(in.resolve(file).toFile());
				JsonNode recording = JSON.readTree(recordings.resolve(cases.get(row.get("recording")).file()).toFile());
				Rechecked rechecked = recheck(incident, recording);
				summaryRows.add(withLevel(header, lines.get(i + 1), rechecked.level()));
				detailRows.addAll(detail(row, rechecked));
				outcomes.add("PASS     " + file + ": " + rechecked.level());
			}
			catch (Refused failed) {
				refused.add(file + ": " + failed.getMessage());
				outcomes.add("REFUSED  " + file + ": " + failed.getMessage());
			}
		}
		int passed = rows.size() - refused.size();
		outcomes.add(refused.isEmpty() ? "RESULT: PASS (" + passed + " of " + rows.size() + " re-rendered)"
				: "RESULT: EXCLUDED (" + refused.size() + " of " + rows.size() + " refused)");
		write(out.resolve("recheck.txt"), outcomes);
		if (refused.isEmpty()) {
			List<String> written = new ArrayList<>();
			written.add(header(header));
			written.addAll(summaryRows);
			write(out.resolve("summary.tsv"), written);
			List<String> detail = new ArrayList<>();
			detail.add(String.join("\t", DETAIL_HEADER));
			detail.addAll(detailRows);
			write(out.resolve("levels-detail.tsv"), detail);
		}
		return new Outcome(passed, refused);
	}

	/** {@code row}, in {@code header}'s columns, with its level set: replaced, or added last when it has none. */
	static String withLevel(List<String> header, String row, String level) {
		List<String> values = new ArrayList<>(Arrays.asList(row.split("\t", -1)));
		int at = header.indexOf(LEVEL);
		if (at < 0) {
			values.add(level);
		}
		else {
			values.set(at, level);
		}
		return String.join("\t", values);
	}

	/** The header of the written {@code summary.tsv}: the input's, with {@code level} last when it has none. */
	static String header(List<String> header) {
		return String.join("\t", header) + (header.contains(LEVEL) ? "" : "\t" + LEVEL);
	}

	private Dossier gather(JsonNode recording, Instant gatheredAt) {
		JsonNode since = recording.path("sinceMinutes");
		if (!since.isIntegralNumber() || since.asInt() <= 0) {
			throw new Refused("the recording has no positive sinceMinutes");
		}
		ReplayLabTools tools = new ReplayLabTools(this.toolsList, List.of(recording));
		return new DossierGatherer(tools, Clock.fixed(gatheredAt, ZoneOffset.UTC), this.topology, Policy.DEFAULT)
			.gather(since.asInt(), new PolicyTally());
	}

	/** The instant the stored dossier was gathered at, which the re-gathering's clock is fixed to. */
	private static Instant gatheredAt(String stored) {
		Matcher matcher = GATHERED.matcher(stored);
		if (!matcher.find()) {
			throw new Refused("the dossier differs: the stored one states no 'Gathered at' instant");
		}
		try {
			return Instant.parse(matcher.group(1));
		}
		catch (DateTimeParseException unreadable) {
			throw new Refused("the stored dossier's 'Gathered at' instant is unreadable: " + matcher.group(1));
		}
	}

	/** Where {@code stored} and {@code rerendered} first differ: the line number and both lines. */
	private static String firstDifference(String stored, String rerendered) {
		String[] a = stored.split("\n", -1);
		String[] b = rerendered.split("\n", -1);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			String left = i < a.length ? a[i] : "<none>";
			String right = i < b.length ? b[i] : "<none>";
			if (!left.equals(right)) {
				return "at message line " + (i + 1) + ": stored '" + cut(left) + "', re-rendered '" + cut(right) + "'";
			}
		}
		return "in its line endings";
	}

	private static String cut(String line) {
		return line.length() <= 120 ? line : line.substring(0, 120) + "...";
	}

	private static Item item(JsonNode evidence, List<LineFact> facts, String cause) {
		String toolCallId = evidence.path("toolCallId").asString("");
		Integer line = evidence.path("line").isIntegralNumber() ? evidence.path("line").asInt() : null;
		if (!"dossier".equals(toolCallId) || line == null || line < 1 || line > facts.size()) {
			return new Item(toolCallId, line, NONE, NONE, NONE, false);
		}
		LineFact fact = facts.get(line - 1);
		return new Item(toolCallId, line, fact.owner(), name(fact.section()), fact.changed().name(),
				fact.owner().equals(cause));
	}

	private static String name(Section section) {
		return section.name().toLowerCase(Locale.ROOT);
	}

	private static List<String> detail(Map<String, String> row, Rechecked rechecked) {
		String truth = row.get("truth");
		String reported = row.get("reported");
		String serviceCorrect = service(reported).equals(service(truth)) ? "yes" : "no";
		String failed = rechecked.failedChecks().isEmpty() ? NONE : String.join(",", rechecked.failedChecks());
		List<String> sample = List.of(row.get("fault"), row.get("recording"), row.get("sample"), truth, reported,
				row.get("verdict"), serviceCorrect, rechecked.level(), failed, rechecked.logOnly() ? "yes" : "no");
		List<String> rows = new ArrayList<>();
		if (rechecked.items().isEmpty()) {
			List<String> values = new ArrayList<>(sample);
			values.addAll(List.of(NONE, NONE, NONE, NONE, NONE, NONE, NONE));
			rows.add(String.join("\t", values));
		}
		for (int i = 0; i < rechecked.items().size(); i++) {
			Item item = rechecked.items().get(i);
			List<String> values = new ArrayList<>(sample);
			values.addAll(List.of(String.valueOf(i + 1), item.toolCallId(),
					item.line() == null ? NONE : String.valueOf(item.line()), item.owner(), item.section(),
					item.changed(), item.causeOwned() ? "yes" : "no"));
			rows.add(String.join("\t", values));
		}
		return rows;
	}

	private static String service(String answer) {
		return answer.split("\\|", 2)[0];
	}

	/**
	 * A summary row by column, checked against the plan's rules.
	 * @throws IllegalArgumentException for a row outside the dev split, or not a decision sample with the tables and
	 * the plain report, the only report this re-check runs as the loop does
	 */
	private static Map<String, String> row(List<String> header, String line, Path summary, int number) {
		String[] values = line.split("\t", -1);
		if (values.length != header.size()) {
			throw new IllegalArgumentException(summary + " line " + number + " has " + values.length + " columns, its "
					+ "header " + header.size());
		}
		Map<String, String> row = new LinkedHashMap<>();
		for (int i = 0; i < values.length; i++) {
			row.put(header.get(i), values[i]);
		}
		for (String column : List.of("fault", "mode", "sample", "truth", "reported", "verdict", "split", "recording",
				"view", "report")) {
			if (!row.containsKey(column)) {
				throw new IllegalArgumentException(summary + " has no column " + column);
			}
		}
		String where = summary + " line " + number;
		if (!SPLIT.equals(row.get("split"))) {
			throw new IllegalArgumentException(where + " has split " + row.get("split") + ": this re-check reads dev only, "
					+ "never a holdout");
		}
		if (!row.get("mode").equals("decision") || !row.get("view").equals("tables")
				|| !row.get("report").equals("plain")) {
			throw new IllegalArgumentException(where + " is " + row.get("mode") + "/" + row.get("view") + "/"
					+ row.get("report") + ": this re-check runs the decision mode's tables and plain report only");
		}
		return row;
	}

	private static List<String> read(Path file) {
		try {
			List<String> lines = Files.readAllLines(file);
			if (lines.isEmpty()) {
				throw new IllegalArgumentException(file + " is empty");
			}
			return lines;
		}
		catch (IOException failed) {
			throw new UncheckedIOException(failed);
		}
	}

	private static void write(Path file, List<String> lines) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, String.join("\n", lines) + "\n");
		}
		catch (IOException failed) {
			throw new UncheckedIOException(failed);
		}
	}

	/**
	 * One re-checked sample.
	 * @param level {@code L0} to {@code L3}, or {@code -} for a run that stored no report
	 * @param failedChecks the names of the checks that failed, in level order
	 * @param items the stored evidence items, each with its cited line's fact
	 * @param logOnly whether the items citing a line the reported cause owns are at least one and all log lines, which
	 * are never compared (ADR-028, Decision 5)
	 */
	record Rechecked(String level, List<String> failedChecks, List<Item> items, boolean logOnly) {

		static final Rechecked NONE = new Rechecked(LevelRecheck.NONE, List.of(), List.of(), false);

	}

	/**
	 * One stored evidence item and its cited line's fact, {@code -} for a citation with no line fact.
	 * @param causeOwned whether the line's owner is the reported cause service
	 */
	record Item(String toolCallId, Integer line, String owner, String section, String changed, boolean causeOwned) {
	}

	/**
	 * A run's outcome.
	 * @param passed the incidents that passed both guards
	 * @param refused each refused incident's file and why
	 */
	record Outcome(int passed, List<String> refused) {
	}

	/** An incident whose re-check would not give the level the run would have stored. */
	static final class Refused extends RuntimeException {

		Refused(String message) {
			super(message);
		}

	}

}
