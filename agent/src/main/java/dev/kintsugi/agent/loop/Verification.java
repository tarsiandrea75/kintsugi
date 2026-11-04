package dev.kintsugi.agent.loop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The verification level of an accepted report and the checks that decide it (ADR-028; agent spec, "Evidence is
 * checked"). Code computes it as an annotation: a level never adds an error, never rejects a report and never causes a
 * retry, so a check never steers the model (vision §3).
 *
 * <p>
 * The checks, by level: L0 {@value #SCHEMA}, the report's schema (only a report that holds it is accepted, so it always
 * passes here); L1 {@value #CITATIONS}, no evidence item was dropped (a summary cut is not a dropped item); L2
 * {@value #OWNERS_ON_PATH}, every cited line's owner is the cause or on its call path, and on the assessed report also
 * {@value #ONE_ORIGIN}, {@value #GRAPH} and {@value #ANSWER}; L3 {@value #CAUSE_FACT_CHANGED}, at least one cited line
 * is owned by the cause and its fact changed between the windows.
 * @param level the highest {@code k} such that every check at levels 0 to {@code k} passes
 * @param checks every check, in level order
 */
record Verification(int level, List<Check> checks) {

	/** L0: the report's schema. */
	static final String SCHEMA = "schema";

	/** L1: every evidence item's line exists, so none was dropped. */
	static final String CITATIONS = "citations";

	/** L2: every cited line's owner is the cause, a transitive caller or a transitive callee of it. */
	static final String OWNERS_ON_PATH = "owners-on-path";

	/** L2, assessed report: at most one ORIGIN, and none only for an all-UNAFFECTED abstention. */
	static final String ONE_ORIGIN = "one-origin";

	/** L2, assessed report: an AFFECTED service calls an ORIGIN or AFFECTED service. */
	static final String GRAPH = "graph";

	/** L2, assessed report: {@code rootCauseService} is the ORIGIN. */
	static final String ANSWER = "answer";

	/** L3: at least one cited line is owned by the cause and its fact changed. */
	static final String CAUSE_FACT_CHANGED = "cause-fact-changed";

	/** The highest level this code computes; L4 (live probes) is out of scope. */
	static final int MAX_LEVEL = 3;

	Verification {
		checks = List.copyOf(checks);
	}

	/**
	 * One check.
	 * @param level the level it belongs to, 0 to {@value Verification#MAX_LEVEL}
	 * @param name its name, one of the constants of {@link Verification}
	 * @param pass true when it passes, false when it fails, null when it does not apply (an abstention's L2 and L3)
	 * @param detail what failed or why it does not apply; null when it passes
	 */
	record Check(int level, String name, Boolean pass, String detail) {

		Check {
			Objects.requireNonNull(name, "name");
		}

		static Check passed(int level, String name) {
			return new Check(level, name, true, null);
		}

		static Check failed(int level, String name, String detail) {
			return new Check(level, name, false, detail);
		}

		/** A check that passes when {@code detail} is null and otherwise fails with it. */
		static Check of(int level, String name, String detail) {
			return detail == null ? passed(level, name) : failed(level, name, detail);
		}

		static Check notApplicable(int level, String name, String detail) {
			return new Check(level, name, null, detail);
		}

	}

	/** The verification of {@code checks}, its level the highest {@code k} whose checks at 0 to {@code k} all pass. */
	static Verification of(List<Check> checks) {
		int level = -1;
		for (int k = 0; k <= MAX_LEVEL; k++) {
			int at = k;
			if (!checks.stream().filter(check -> check.level() == at).allMatch(check -> Boolean.TRUE.equals(check.pass()))) {
				break;
			}
			level = k;
		}
		if (level < 0) {
			throw new IllegalArgumentException("an accepted report passes its schema: " + checks);
		}
		return new Verification(level, checks);
	}

	/**
	 * A level as it is stored and reported: {@code L0} to {@code L3} (verification-levels T4). The one place that writes
	 * a level, so the report's level and each check's read the same.
	 */
	static String label(int level) {
		if (level < 0 || level > MAX_LEVEL) {
			throw new IllegalArgumentException("a level is 0 to " + MAX_LEVEL + ", got " + level);
		}
		return "L" + level;
	}

	/** The check named {@code name}, when there is one. */
	Optional<Check> check(String name) {
		return this.checks.stream().filter(check -> check.name().equals(name)).findFirst();
	}

}
