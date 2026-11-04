package dev.kintsugi.agent.loop;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The level re-check run (verification-levels T6), only through {@code infra/scripts/recheck-levels.sh}: it reads a
 * replay run's directory ({@code recheck.in}), the replay directory that run read ({@code replay.recordings}) and the
 * topology ({@code kintsugi.agent.topology}), and writes to {@code recheck.out}. It calls no model and opens no
 * database. Tagged {@code replay}, so the default test run leaves it out.
 */
@Tag("replay")
class LevelRecheckRunTest {

	@Test
	void recheck() {
		Path in = Path.of(required("recheck.in"));
		Path out = Path.of(required("recheck.out"));
		if (Files.exists(out)) {
			throw new IllegalArgumentException(out + " exists: a re-check never overwrites one");
		}
		Path recordings = Path.of(System.getProperty("replay.recordings", ReplayDriver.DEFAULT_RECORDINGS.toString()));
		LevelRecheck recheck = new LevelRecheck(ReplayDriver.DEFAULT_TOOLS_LIST,
				ReplayDriver.topology(System::getProperty));
		LevelRecheck.Outcome outcome = recheck.run(in, recordings, out);
		System.out.println("re-checked " + in + ": " + outcome.passed() + " passed, " + outcome.refused().size()
				+ " refused; out: " + out);
	}

	private static String required(String property) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(property + " is required");
		}
		return value;
	}

}
