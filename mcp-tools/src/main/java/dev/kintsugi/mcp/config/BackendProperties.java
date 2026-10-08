package dev.kintsugi.mcp.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the observability tools find Loki and Prometheus, and how long they wait for them. Defaults are in
 * {@code application.properties}: the compose service names, a 2 s connect timeout and a 5 s read timeout.
 */
@ConfigurationProperties("kintsugi.mcp")
public record BackendProperties(Backend loki, Backend prometheus) {

	public record Backend(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
	}

}
