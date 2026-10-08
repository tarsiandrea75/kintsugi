package dev.kintsugi.agent.loop;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Ollama route, {@code kintsugi.agent.model} (ADR-003 for the model and its settings, ADR-010 for the timeout).
 *
 * @param name the model, {@code granite4.1:8b}
 * @param temperature 0, so a run is as repeatable as the model allows
 * @param numCtx 8192 tokens
 * @param callTimeout 90 s per model call
 */
@ConfigurationProperties("kintsugi.agent.model")
public record ModelRouteProperties(String name, double temperature, int numCtx, Duration callTimeout) {
}
