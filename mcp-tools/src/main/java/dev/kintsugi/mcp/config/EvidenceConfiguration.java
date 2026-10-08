package dev.kintsugi.mcp.config;

import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.kintsugi.mcp.evidence.Database;
import dev.kintsugi.mcp.evidence.LabDatabases;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One small, read-only connection pool per lab database, all as {@code kintsugi_agent} (ADR-004). They replace Spring
 * Boot's single auto-configured DataSource, which backs off when DataSource beans exist.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class EvidenceConfiguration {

	@Bean
	HikariDataSource ordersDataSource(DatabaseProperties properties) {
		return dataSource(properties, Database.ORDERS);
	}

	@Bean
	HikariDataSource paymentsDataSource(DatabaseProperties properties) {
		return dataSource(properties, Database.PAYMENTS);
	}

	@Bean
	HikariDataSource inventoryDataSource(DatabaseProperties properties) {
		return dataSource(properties, Database.INVENTORY);
	}

	@Bean
	LabDatabases labDatabases(@Qualifier("ordersDataSource") HikariDataSource orders,
			@Qualifier("paymentsDataSource") HikariDataSource payments,
			@Qualifier("inventoryDataSource") HikariDataSource inventory) {
		return new LabDatabases(Map.of(Database.ORDERS, orders, Database.PAYMENTS, payments, Database.INVENTORY,
				inventory));
	}

	/**
	 * A pool for {@code database}. Connections are read-only on two levels, on top of the role's own defaults:
	 * Hikari marks each connection read-only, and pgjdbc's {@code readOnlyMode=always} makes even auto-commit
	 * statements run in a read-only session. The pool opens no connection at startup, so mcp-tools starts while the
	 * database is down and its tools answer {@code UPSTREAM_ERROR} until it is back.
	 */
	public static HikariDataSource dataSource(DatabaseProperties properties, Database database) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("mcp-tools-" + database.databaseName());
		config.setJdbcUrl("jdbc:postgresql://" + properties.host() + ":" + properties.port() + "/"
				+ database.databaseName());
		config.setUsername(properties.username());
		config.setPassword(properties.password());
		config.setReadOnly(true);
		config.addDataSourceProperty("readOnlyMode", "always");
		config.addDataSourceProperty("ApplicationName", "mcp-tools");
		// pgjdbc's connect timeout is in whole seconds; at least 1.
		config.addDataSourceProperty("connectTimeout",
				String.valueOf(Math.max(1, properties.connectionTimeout().toSeconds())));
		config.setConnectionTimeout(properties.connectionTimeout().toMillis());
		config.setMaximumPoolSize(properties.maximumPoolSize());
		config.setMinimumIdle(0);
		config.setInitializationFailTimeout(-1);
		return new HikariDataSource(config);
	}

}
