package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.incident.IncidentRepository;

/**
 * The replay run (agent spec, "Replay of recorded tool outputs"; odd/tasks/replay-harness.md T4): every recorded fault
 * replayed N times per mode through the real loop and the route's real model, each sample scored against the recorded
 * truth. It costs model calls, so it is tagged {@code replay}, left out of the default build and run only by
 * {@code infra/scripts/replay-agent.sh} ({@code ./mvnw test -Preplay -Dtest=ReplayRunTest}).
 *
 * <p>
 * System properties: {@code replay.route} ({@code cloud}, the default, or {@code local}), {@code replay.mode}
 * ({@code explorer}, {@code dossier} or {@code both}, the default), {@code replay.faults} ({@code F1,F2,F3}),
 * {@code replay.samples} (1), {@code replay.recordings} (the committed {@code replay/} directory) and {@code replay.out}
 * ({@code infra/scripts/out/replay-<UTC time>-<route>/}). A miss is a measurement, not a failure: the test fails only
 * when it cannot run a sample.
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

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void replay() throws IOException {
		String route = System.getProperty("replay.route", "cloud");
		List<AgentMode> modes = modes(System.getProperty("replay.mode", "both"));
		List<String> faults = Arrays.stream(System.getProperty("replay.faults", "F1,F2,F3").split(","))
			.map(String::strip)
			.filter(fault -> !fault.isEmpty())
			.toList();
		int samples = Integer.parseInt(System.getProperty("replay.samples", "1"));
		Path recordings = Path.of(System.getProperty("replay.recordings", ReplayDriver.DEFAULT_RECORDINGS.toString()));
		String time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
		Path out = Path.of(System.getProperty("replay.out",
				REPO.resolve("infra/scripts/out/replay-" + time + "-" + route).toString()));
		assertThat(this.router.route(ModelTask.ACT).route().value()).as("the context's route").isEqualTo(route);
		assertThat(samples).as("replay.samples").isPositive();
		assertThat(faults).as("replay.faults").isNotEmpty();

		AgentDatabase.empty();
		ReplayDriver driver = new ReplayDriver(this.router, this.settings, this.incidents, AgentDatabase.admin(),
				recordings, ReplayDriver.DEFAULT_TOOLS_LIST);
		List<ReplayDriver.Sample> done = new ArrayList<>();
		try {
			for (String fault : faults) {
				for (AgentMode mode : modes) {
					for (int n = 1; n <= samples; n++) {
						ReplayDriver.Sample sample = driver.sample(fault, mode, n);
						ReplayDriver.write(out, sample);
						done.add(sample);
						System.out.println("replay " + sample.summaryLine());
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
