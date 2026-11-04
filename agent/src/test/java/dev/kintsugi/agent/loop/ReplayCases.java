package dev.kintsugi.agent.loop;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The replay manifest, {@code recordings.tsv}, and the recordings a replay run takes from it (agent spec, "Replay of
 * recorded tool outputs"; odd/tasks/decision-instrument.md T3): the one place that choice is made. {@link ReplayDriver}
 * replays what it picks, and {@code infra/scripts/replay-agent.sh} counts a decision run's cases with it before Maven,
 * running this file from its source with the JDK's launcher, in about a second:
 *
 * <pre>
 * java agent/src/test/java/dev/kintsugi/agent/loop/ReplayCases.java &lt;replay dir&gt; &lt;split&gt; &lt;F1,F2,...&gt;
 * </pre>
 *
 * <p>
 * The launcher compiles this file alone, so it uses the JDK only: it names the modes as the manifest records them,
 * which {@code ReplayDriverTest} checks against {@code AgentMode}.
 */
final class ReplayCases {

	/**
	 * The splits of {@code recordings.tsv} (odd/tasks/decision-instrument.md): {@code dev} to tune on, {@code holdout}
	 * read once, {@code stress} for recordings whose windows hold other faults, {@code probe} for faults never tuned
	 * on, and {@code holdout-2} for the cases of holdout-2's schedule, recorded after the freeze (ADR-026).
	 */
	static final List<String> SPLITS = List.of("dev", "holdout", "stress", "probe", "holdout-2");

	/**
	 * The split explorer and dossier samples pool: the 2026-10-19 recordings they were measured on. An import into
	 * another split never changes them, and a holdout recording never reaches them.
	 */
	static final String POOLED_SPLIT = "stress";

	/** The modes, as the manifest records them, whose recordings hold the whole dossier: those of a decision case. */
	static final List<String> CASE_MODES = List.of("decision", "dossier");

	private final Path manifest;

	private final List<Recording> rows;

	private ReplayCases(Path manifest, List<Recording> rows) {
		this.manifest = manifest;
		this.rows = rows;
	}

	/**
	 * Reads {@code recordings.tsv} in the replay directory {@code recordings}, one row per line: fault, mode, route,
	 * truth, file, incident, split and case; the header is skipped. The case, holdout-2's schedule case (ADR-026), is
	 * empty on every other row, and a row of seven columns, as the RCAEval converter writes, has none.
	 * @throws IllegalArgumentException for a row with fewer columns, or a split not in {@link #SPLITS}
	 * @throws UncheckedIOException when the manifest cannot be read
	 */
	static ReplayCases read(Path recordings) {
		Path manifest = recordings.resolve("recordings.tsv");
		List<String> lines;
		try {
			lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException("cannot read the recordings manifest " + manifest, unreadable);
		}
		List<Recording> rows = new ArrayList<>();
		for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			String[] columns = line.split("\t", -1);
			if (columns.length < 7) {
				throw new IllegalArgumentException("a manifest line needs 7 columns: " + line);
			}
			if (!SPLITS.contains(columns[6])) {
				throw new IllegalArgumentException(
						"a manifest line has the split '" + columns[6] + "', not " + splitChoices() + ": " + line);
			}
			rows.add(new Recording(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5],
					columns[6], columns.length > 7 ? columns[7] : ""));
		}
		return new ReplayCases(manifest, List.copyOf(rows));
	}

	/**
	 * The recordings an explorer or dossier sample of {@code fault} is served: the fault's rows of
	 * {@link #POOLED_SPLIT}, every mode, in manifest order.
	 * @throws IllegalArgumentException when the fault has none
	 */
	List<Recording> pooled(String fault) {
		List<Recording> pooled = this.rows.stream()
			.filter(recording -> recording.fault().equals(fault) && recording.split().equals(POOLED_SPLIT))
			.toList();
		if (pooled.isEmpty()) {
			throw new IllegalArgumentException(
					"no recording of " + fault + " in split " + POOLED_SPLIT + " of " + this.manifest);
		}
		return pooled;
	}

	/**
	 * The decision cases of {@code split} for {@code faults}: one per row of a chosen fault in that split made in one
	 * of {@link #CASE_MODES}. The faults come in the order given, each fault's rows in manifest order.
	 * @throws IllegalArgumentException for a split not in {@link #SPLITS}, or a chosen fault with no case in it
	 */
	List<Recording> decisionCases(String split, List<String> faults) {
		if (!SPLITS.contains(split)) {
			throw new IllegalArgumentException("unknown split '" + split + "': choose " + splitChoices());
		}
		List<Recording> cases = new ArrayList<>();
		List<String> without = new ArrayList<>();
		for (String fault : faults) {
			List<Recording> ofFault = this.rows.stream()
				.filter(recording -> recording.fault().equals(fault) && recording.split().equals(split)
						&& CASE_MODES.contains(recording.mode()))
				.toList();
			if (ofFault.isEmpty()) {
				without.add(fault);
			}
			cases.addAll(ofFault);
		}
		if (!without.isEmpty()) {
			throw new IllegalArgumentException("no decision or dossier recording of " + String.join(", ", without)
					+ " in split " + split + " of " + this.manifest);
		}
		return List.copyOf(cases);
	}

	/** The faults of a comma-separated list, as {@code replay.faults} takes them: each one stripped, blanks dropped. */
	static List<String> faults(String list) {
		return Arrays.stream(list.split(",")).map(String::strip).filter(fault -> !fault.isEmpty()).toList();
	}

	/** {@link #SPLITS} as the messages name them: {@code dev, holdout, stress, probe or holdout-2}. */
	static String splitChoices() {
		return String.join(", ", SPLITS.subList(0, SPLITS.size() - 1)) + " or " + SPLITS.get(SPLITS.size() - 1);
	}

	/**
	 * The listing {@code replay-agent.sh} counts. Given {@code <replay dir> <split> <faults>}, it prints the decision
	 * cases {@link #decisionCases} picks, one {@code <fault> TAB <file>} line each, and returns 0. When it refuses them
	 * (an unknown split, a chosen fault with no case, a manifest it cannot read or a line it cannot take), or for other
	 * arguments, it prints why to {@code err} and returns 2, the script's status for a bad option.
	 */
	static int list(List<String> args, PrintStream out, PrintStream err) {
		if (args.size() != 3) {
			err.println("usage: java ReplayCases.java <replay dir> <split> <fault>[,<fault>...]");
			return 2;
		}
		try {
			read(Path.of(args.get(0))).decisionCases(args.get(1), faults(args.get(2)))
				.forEach(recording -> out.println(recording.fault() + "\t" + recording.file()));
			return 0;
		}
		catch (IllegalArgumentException | UncheckedIOException refused) {
			err.println(refused.getMessage());
			return 2;
		}
	}

	/** Runs {@link #list} on the command line's arguments, from the JDK's source launcher, and exits with its status. */
	public static void main(String[] args) {
		System.exit(list(List.of(args), System.out, System.err));
	}

	/**
	 * One row of {@code recordings.tsv}; {@code file} is relative to the replay directory, and {@code scheduleCase} is
	 * the holdout-2 schedule case it records, empty when it records none.
	 */
	record Recording(String fault, String mode, String route, String truth, String file, String incident, String split,
			String scheduleCase) {

		/** The file's name without its directory and {@code .json}: what names the recording in a sample's outputs. */
		String stem() {
			String name = Path.of(this.file).getFileName().toString();
			return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
		}

	}

}
