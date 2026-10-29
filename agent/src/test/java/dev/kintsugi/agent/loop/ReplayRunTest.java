package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.incident.IncidentRepository;

/**
 * The replay run (agent spec, "Replay of recorded tool outputs"; odd/tasks/replay-harness.md T4,
 * odd/tasks/decision-instrument.md T3): recorded faults replayed through the real loop and the route's real model, each
 * sample scored against the recorded truth. It costs model calls, so it is tagged {@code replay}, left out of the
 * default build and run only by {@code infra/scripts/replay-agent.sh}
 * ({@code ./mvnw test -Preplay -Dtest=ReplayRunTest}).
 *
 * <p>
 * System properties: {@code replay.route} ({@code cloud}, the default, or {@code local}; Config 0's {@code rules}
 * route is {@link RulesReplayTest}, which has no Spring context, so this context never sees it), {@code replay.mode}
 * ({@code dossier}, the default, {@code explorer}, {@code decision} or {@code both}, which is explorer and dossier; an
 * explorer replay is low-coverage and not a valid measurement, see the feature document's T4 smoke check),
 * {@code replay.faults} ({@code F1,F2,F3}), {@code replay.recordings} (the committed {@code replay/} directory) and
 * {@code replay.out} ({@code infra/scripts/out/replay-<UTC time>-<route>/}). Explorer and dossier replay each fault
 * {@code replay.samples} (1) times per mode, over the fault's {@code stress} recordings. Decision mode replays each
 * recording of the split {@code replay.split} ({@code dev}, the default, {@code holdout}, {@code stress} or
 * {@code probe}) made in decision or dossier mode, alone, {@code replay.repeats} (1) times: the dossier's recorded calls
 * and the report call. {@link ReplayCases} picks the recordings. A property of the other kind of run is refused rather
 * than ignored ({@link ReplayDriver#refuseIgnoredProperties}), so a run is never other than the one asked for. A
 * decision run writes its dossier in the view of {@code kintsugi.agent.decision.view} ({@code tables}, the default, or
 * {@code sheets}; odd/tasks/service-assessment.md T1) and asks for the report of
 * {@code kintsugi.agent.decision.report} ({@code plain}, the default, or {@code assessed}, which needs the sheets; T2r),
 * each set as a system property like the others, and records both with each sample; explorer and dossier runs refuse
 * them. A miss is a measurement, not a failure: the test fails only when it
 * cannot run a sample.
 *
 * <p>
 * The application context supplies what the app would run with: the route's {@code ChatModel} inside its
 * {@link ModelRouter}, {@link LoopSettings} and the incident store. The loop itself is built per sample by
 * {@link ReplayDriver}, over that sample's tools and clock.
 */
@Tag("replay")
@SpringBootTest(properties = "kintsugi.agent.route=${replay.route:cloud}")
class ReplayRunTest {

	private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

	@Autowired
	ModelRouter router;

	@Autowired
	LoopSettings settings;

	@Autowired
	IncidentRepository incidents;

	/** The application's own binding of the view, so a value the agent would refuse fails here too. */
	@Value("${" + DossierView.PROPERTY + ":" + DossierView.DEFAULT + "}")
	String decisionView;

	/** The application's own binding of the report, likewise. */
	@Value("${" + DecisionReport.PROPERTY + ":" + DecisionReport.DEFAULT + "}")
	String decisionReport;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void replay() throws IOException {
		String route = System.getProperty("replay.route", "cloud");
		List<AgentMode> modes = modes(System.getProperty("replay.mode", "dossier"));
		boolean decision = modes.equals(List.of(AgentMode.DECISION));
		List<String> faults = ReplayCases.faults(System.getProperty("replay.faults", "F1,F2,F3"));
		ReplayDriver.refuseIgnoredProperties(decision, System::getProperty);
		int samples = Integer.parseInt(System.getProperty("replay.samples", "1"));
		String split = System.getProperty("replay.split", "dev");
		int repeats = Integer.parseInt(System.getProperty("replay.repeats", "1"));
		Path recordings = Path.of(System.getProperty("replay.recordings", ReplayDriver.DEFAULT_RECORDINGS.toString()));
		String time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
		Path out = Path.of(System.getProperty("replay.out",
				REPO.resolve("infra/scripts/out/replay-" + time + "-" + route).toString()));
		assertThat(this.router.route(ModelTask.ACT).route().value()).as("the context's route").isEqualTo(route);
		assertThat(samples).as("replay.samples").isPositive();
		assertThat(repeats).as("replay.repeats").isPositive();
		assertThat(faults).as("replay.faults").isNotEmpty();

		AgentDatabase.empty();
		ReplayDriver driver = new ReplayDriver(this.router, this.settings, this.incidents, AgentDatabase.admin(),
				recordings, ReplayDriver.DEFAULT_TOOLS_LIST, DossierView.of(this.decisionView),
				DecisionReport.of(this.decisionReport));
		List<ReplayDriver.Sample> done = new ArrayList<>();
		Consumer<ReplayDriver.Sample> record = sample -> {
			// Counted before it is written: a paid sample stays in aggregate.tsv even when its write fails.
			done.add(sample);
			ReplayDriver.write(out, sample);
			System.out.println("replay " + sample.summaryLine());
		};
		try {
			if (decision) {
				driver.decisionSamples(split, faults, repeats, record);
			}
			else {
				for (String fault : faults) {
					for (AgentMode mode : modes) {
						for (int n = 1; n <= samples; n++) {
							record.accept(driver.sample(fault, mode, n));
						}
					}
				}
			}
		}
		finally {
			// Written for the samples that ran even when a later one fails, so a paid partial run still has its totals.
			// A failure here is only reported, so it never hides the sample's own failure that brought us here.
			List<String> aggregate = ReplayDriver.aggregate(done);
			try {
				Files.writeString(out.resolve("aggregate.tsv"), String.join("\n", aggregate) + "\n");
			}
			catch (IOException unwritten) {
				System.err.println("replay: aggregate.tsv not written: " + unwritten);
			}
			System.out.println("replay out: " + out);
			aggregate.forEach(line -> System.out.println("replay " + line));
		}
	}

	private static List<AgentMode> modes(String mode) {
		return mode.equals("both") ? List.of(AgentMode.EXPLORER, AgentMode.DOSSIER) : List.of(AgentMode.of(mode));
	}

}
