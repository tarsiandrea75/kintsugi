package dev.kintsugi.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The runbook authoring rule's check, run on every allowed file before anything is embedded or written: a fault id,
 * or a word that starts with "fault" or "inject", in any case. "default" passes, because it does not start with
 * "fault". Only the README's own "Authoring rule" section, which states the rule, is exempt.
 */
final class LeakageGate {

	static final Pattern LEAKAGE = Pattern.compile("\\b(F[1-8]\\b|fault|inject)", Pattern.CASE_INSENSITIVE);

	private LeakageGate() {
	}

	/** Stops the run, naming every matching file and line, when any runbook leaks. */
	static void check(List<Runbook> runbooks) {
		List<String> matches = new ArrayList<>();
		for (Runbook runbook : runbooks) {
			for (int line = 0; line < runbook.lines().size(); line++) {
				if (!runbook.isExemptLine(line) && LEAKAGE.matcher(runbook.lines().get(line)).find()) {
					matches.add(runbook.source() + ":" + (line + 1) + ": " + runbook.lines().get(line).strip());
				}
			}
		}
		if (!matches.isEmpty()) {
			throw new IndexingException("Leakage gate: runbooks must not name faults (docs/runbooks/README.md,"
					+ " \"Authoring rule\"); nothing was indexed. Matches:\n  " + String.join("\n  ", matches));
		}
	}

}
