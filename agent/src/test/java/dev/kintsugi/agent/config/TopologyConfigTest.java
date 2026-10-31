package dev.kintsugi.agent.config;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.kintsugi.agent.dossier.Topology;

/**
 * The one topology bean (odd/tasks/topology-data.md T3): the lab's file when {@code kintsugi.agent.topology} is not
 * set, the named file when it is, and a start that fails, naming the file, when that file is missing or invalid.
 */
class TopologyConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(TopologyConfig.class);

	@Test
	void theDefaultIsTheLabFile() {
		this.runner.run(context -> assertThat(context.getBean(Topology.class)).isEqualTo(LAB));
	}

	@Test
	void thePropertyNamesTheFile() {
		this.runner.withPropertyValues(Topology.PROPERTY + "=classpath:topology/lab.json")
			.run(context -> assertThat(context.getBean(Topology.class)).isEqualTo(LAB));
	}

	@Test
	void aMissingFileStopsTheStart() {
		this.runner.withPropertyValues(Topology.PROPERTY + "=classpath:topology/no-such-file.json")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no-such-file.json"));
	}

	@Test
	void anInvalidFileStopsTheStart(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("cycle.json"), """
				{"services": ["a", "b"], "datastores": [],
				 "edges": [{"caller": "a", "target": "b", "method": "GET", "uri": "/"},
				           {"caller": "b", "target": "a", "method": "GET", "uri": "/"}],
				 "tableOwners": {}, "sourceOwners": {}}""");
		this.runner.withPropertyValues(Topology.PROPERTY + "=" + file.toUri())
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				// Topology.load wraps the cycle, found by the record's check, in the error that names the file.
				.hasStackTraceContaining("IllegalArgumentException: invalid topology URL [file:")
				.hasStackTraceContaining("cycle.json]: cycle: a -> b -> a"));
	}

}
