package dev.kintsugi.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import dev.kintsugi.agent.dossier.Topology;

/**
 * The one {@link Topology} the agent reads (odd/tasks/topology-data.md T3): the file named by
 * {@code kintsugi.agent.topology}, a Spring resource location, the lab's file by default
 * ({@link Topology#DEFAULT_LOCATION}). It loads at startup, so a missing or invalid file stops the start with a message
 * naming the file and the problem, never a later run.
 */
@Configuration(proxyBeanMethods = false)
public class TopologyConfig {

	@Bean
	Topology topology(@Value("${" + Topology.PROPERTY + ":" + Topology.DEFAULT_LOCATION + "}") Resource location) {
		return Topology.load(location);
	}

}
