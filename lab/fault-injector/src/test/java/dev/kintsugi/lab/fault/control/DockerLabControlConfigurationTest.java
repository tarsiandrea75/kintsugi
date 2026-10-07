package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.catalog.LabMechanism;

/** {@code kintsugi.lab-control.mode=docker} selects the routing control; any other mode leaves it out. */
class DockerLabControlConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(DockerLabControlConfiguration.class, UnsupportedLabControl.class);

	@TempDir
	Path repo;

	@Test
	void dockerModeSelectsTheRoutingControlOnly() throws Exception {
		Path composeFile = Files.writeString(repo.resolve("compose.yaml"), "name: kintsugi-lab\n");
		runner.withPropertyValues("kintsugi.lab-control.mode=docker", "kintsugi.lab-control.compose-file=" + composeFile)
			.run(context -> {
				assertThat(context).hasSingleBean(LabControl.class);
				assertThat(context).getBean(LabControl.class).isInstanceOf(RoutingLabControl.class);
				assertThat(context.getBean(LabControlProperties.class)).satisfies(properties -> {
					assertThat(properties.gatewayBaseUrl()).hasToString("http://gateway-mock:8082");
					assertThat(properties.composeProject()).isEqualTo("kintsugi-lab");
					assertThat(properties.recreateWaitTimeout()).hasSeconds(120);
				});
			});
	}

	@Test
	void dockerModeWithoutAComposeFileFailsAtStartup() {
		runner.withPropertyValues("kintsugi.lab-control.mode=docker")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("kintsugi.lab-control.compose-file"));
	}

	@Test
	void dockerModeWithAMissingComposeFileFailsAtStartup() {
		// A typo or a repository mounted at the wrong path must stop the injector at startup, not at the first F3.
		runner.withPropertyValues("kintsugi.lab-control.mode=docker",
				"kintsugi.lab-control.compose-file=" + repo.resolve("missing/compose.yaml"))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("does not exist or is not readable"));
	}

	@Test
	void dockerModeWithADirectoryAsComposeFileFailsAtStartup() {
		runner.withPropertyValues("kintsugi.lab-control.mode=docker", "kintsugi.lab-control.compose-file=" + repo)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.hasStackTraceContaining("does not exist or is not readable"));
	}

	@Test
	void unsupportedModeKeepsThePlaceholder() {
		runner.withPropertyValues("kintsugi.lab-control.mode=unsupported")
			.run(context -> assertThat(context).getBean(LabControl.class).isInstanceOf(UnsupportedLabControl.class));
	}

	@Test
	void routingSendsEachFaultToTheControlOfItsMechanism() {
		List<String> calls = new ArrayList<>();
		RoutingLabControl routing = new RoutingLabControl(
				Map.of(LabMechanism.GATEWAY_BEHAVIOR, recording("gateway", calls), LabMechanism.COMPOSE_RECREATE,
						recording("compose", calls)));

		routing.activate(Fault.F1);
		routing.restore(Fault.F2);
		routing.activate(Fault.F3);
		routing.restore(Fault.F4);

		assertThat(calls).containsExactly("gateway activate F1", "gateway restore F2", "compose activate F3",
				"compose restore F4");
	}

	@Test
	void routingWithoutAControlForAMechanismIsRejected() {
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
			.isThrownBy(() -> new RoutingLabControl(Map.of(LabMechanism.GATEWAY_BEHAVIOR, recording("g", List.of()))));
	}

	private static LabControl recording(String name, List<String> calls) {
		return new LabControl() {

			@Override
			public void activate(Fault fault) {
				calls.add(name + " activate " + fault.id());
			}

			@Override
			public void restore(Fault fault) {
				calls.add(name + " restore " + fault.id());
			}

		};
	}

}
