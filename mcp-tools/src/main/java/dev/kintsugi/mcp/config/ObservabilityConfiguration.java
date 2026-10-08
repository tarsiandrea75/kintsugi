package dev.kintsugi.mcp.config;

import java.time.Clock;

import dev.kintsugi.mcp.observability.BackendClients;
import dev.kintsugi.mcp.observability.LokiClient;
import dev.kintsugi.mcp.observability.PrometheusClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Loki and Prometheus clients behind the observability tools, and the clock that sets their time windows. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackendProperties.class)
public class ObservabilityConfiguration {

	@Bean
	LokiClient lokiClient(BackendProperties properties) {
		return new LokiClient(BackendClients.restClient(properties.loki()));
	}

	@Bean
	PrometheusClient prometheusClient(BackendProperties properties) {
		return new PrometheusClient(BackendClients.restClient(properties.prometheus()));
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
