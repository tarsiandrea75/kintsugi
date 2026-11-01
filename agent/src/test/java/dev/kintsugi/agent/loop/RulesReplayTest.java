package dev.kintsugi.agent.loop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.DossierView;

/**
 * The replay run of Config 0 (ADR-016; agent spec, "Replay of recorded tool outputs"; odd/tasks/rules-baseline.md T3):
 * every decision case of a split diagnosed by the rules, written as {@link ReplayRunTest} writes a model's run, so the
 * scorer reads it as one more config. Run only by {@code infra/scripts/replay-agent.sh --route rules}
 * ({@code ./mvnw test -Preplay -Dtest=RulesReplayTest}); tagged {@code replay} because it writes a run directory, not
 * because it costs anything.
 *
 * <p>
 * It has no Spring context, on purpose: a rules sample needs no router, no model, no loop settings and no incident
 * store ({@link ReplayDriver#rulesOnly}), so nothing here selects a route, and production's routes stay closed to
 * {@code rules}. The view and the report are read from {@link DossierView#PROPERTY} and {@link DecisionReport#PROPERTY}
 * with the application's defaults and recorded with each sample; the rules read the typed dossier, so neither changes
 * a verdict. The rules version is read from {@code kintsugi.agent.rules}, {@code v1} (Config 0) by default or
 * {@code v2} (Config 0R; resource-facts T5), and recorded as each sample's model.
 *
 * <p>
 * System properties: {@code replay.mode} ({@code decision}, the only mode it replays; any other is refused),
 * {@code replay.split}, {@code replay.repeats}, {@code replay.faults}, {@code replay.recordings} and
 * {@code replay.out} ({@code infra/scripts/out/replay-<UTC time>-rules/}), as {@link ReplayRunTest} reads them, and
 * {@code replay.samples} is refused as there.
 */
@Tag("replay")
class RulesReplayTest {

	private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	@Test
	void replay() throws IOException {
		String mode = System.getProperty("replay.mode", AgentMode.DECISION.value());
		if (!mode.equals(AgentMode.DECISION.value())) {
			throw new IllegalArgumentException("the rules route replays decision mode only, not '" + mode
					+ "': Config 0 diagnoses the gathered dossier and has no explorer or dossier loop");
		}
		String route = System.getProperty("replay.route", ReplayDriver.RULES_ROUTE);
		if (!route.equals(ReplayDriver.RULES_ROUTE)) {
			throw new IllegalArgumentException("RulesReplayTest runs the rules route, not '" + route
					+ "': a model's route runs ReplayRunTest");
		}
		ReplayDriver.refuseIgnoredProperties(true, System::getProperty);
		List<String> faults = ReplayCases.faults(System.getProperty("replay.faults", "F1,F2,F3"));
		String split = System.getProperty("replay.split", "dev");
		int repeats = Integer.parseInt(System.getProperty("replay.repeats", "1"));
		if (faults.isEmpty() || repeats < 1) {
			throw new IllegalArgumentException("replay.faults needs a fault and replay.repeats a positive count");
		}
		Path recordings = Path.of(System.getProperty("replay.recordings", ReplayDriver.DEFAULT_RECORDINGS.toString()));
		String time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
		Path out = Path.of(System.getProperty("replay.out",
				REPO.resolve("infra/scripts/out/replay-" + time + "-" + route).toString()));

		ReplayDriver driver = ReplayDriver.rulesOnly(recordings, ReplayDriver.DEFAULT_TOOLS_LIST,
				DossierView.of(System.getProperty(DossierView.PROPERTY, DossierView.DEFAULT)),
				DecisionReport.of(System.getProperty(DecisionReport.PROPERTY, DecisionReport.DEFAULT)));
		List<ReplayDriver.Sample> done = new ArrayList<>();
		try {
			driver.rulesSamples(split, faults, repeats, sample -> {
				done.add(sample);
				ReplayDriver.write(out, sample);
				System.out.println("replay " + sample.summaryLine());
			});
		}
		finally {
			// As ReplayRunTest does: a failure here is only reported, so it never hides a sample's own failure.
			List<String> aggregate = ReplayDriver.aggregate(done);
			try {
				Files.createDirectories(out);
				Files.writeString(out.resolve("aggregate.tsv"), String.join("\n", aggregate) + "\n");
			}
			catch (IOException unwritten) {
				System.err.println("replay: aggregate.tsv not written: " + unwritten);
			}
			System.out.println("replay out: " + out);
			aggregate.forEach(line -> System.out.println("replay " + line));
		}
	}

}
