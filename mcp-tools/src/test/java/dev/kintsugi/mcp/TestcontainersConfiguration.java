package dev.kintsugi.mcp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Pinned to the PostgreSQL version chosen in ADR-007 (docs/adr/ADR-007-lab-persistence-stack.md).
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
	}

}
