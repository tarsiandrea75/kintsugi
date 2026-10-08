package dev.kintsugi.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the event and summary tools find the lab's PostgreSQL, and as whom they connect. The database names are not
 * configurable: they are fixed in {@link dev.kintsugi.mcp.evidence.Database}, so no setting can point a pool at
 * {@code faults}. Defaults are in {@code application.properties}.
 *
 * @param host the PostgreSQL host
 * @param port the PostgreSQL port
 * @param username always {@code kintsugi_agent} in the lab (ADR-004)
 * @param password that role's password
 * @param connectionTimeout how long a tool call waits for a connection, from the pool or a new one
 * @param maximumPoolSize connections per database
 */
@ConfigurationProperties("kintsugi.mcp.postgres")
public record DatabaseProperties(String host, int port, String username, String password, Duration connectionTimeout,
		int maximumPoolSize) {

}
