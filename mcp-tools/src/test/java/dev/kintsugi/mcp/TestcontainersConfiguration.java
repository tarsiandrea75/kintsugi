package dev.kintsugi.mcp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Points the application's {@code kintsugi_agent} pools at the shared {@link LabDatabase} container, built by the real
 * init script and the services' migrations.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	DynamicPropertyRegistrar labDatabaseProperties() {
		return registry -> {
			registry.add("kintsugi.mcp.postgres.host", LabDatabase::host);
			registry.add("kintsugi.mcp.postgres.port", LabDatabase::port);
			registry.add("kintsugi.mcp.postgres.password", () -> LabDatabase.AGENT_PASSWORD);
		};
	}

}
