package dev.kintsugi.agent.dossier;

import org.springframework.core.io.DefaultResourceLoader;

/**
 * The lab's topology for tests (odd/tasks/topology-data.md T3), loaded once from the file the agent loads by default,
 * {@link Topology#DEFAULT_LOCATION}, so a test reads the same topology as the running agent.
 */
public final class LabTopology {

	/** The lab's topology, from {@code topology/lab.json}. */
	public static final Topology LAB = Topology.load(new DefaultResourceLoader().getResource(Topology.DEFAULT_LOCATION));

	private LabTopology() {
	}

}
