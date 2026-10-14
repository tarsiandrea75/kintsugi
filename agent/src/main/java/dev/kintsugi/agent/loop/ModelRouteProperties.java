package dev.kintsugi.agent.loop;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Ollama route, {@code kintsugi.agent.model} (ADR-003 for the model and its settings, ADR-010 for the timeout).
 *
 * @param name the model, {@code granite4.1:8b}
 * @param temperature 0, so a run is as repeatable as the model allows
 * @param numCtx 8192 tokens
 * @param callTimeout 180 s per model call (the human's budget decision of 2026-10-12, from the measured CPU latency)
 * @param numPredict the answer caps: 384 tokens for a phase's first {@code ACT} answer, 160 for a later one, 512 for
 * the report (agent spec, "Answer caps")
 */
@ConfigurationProperties("kintsugi.agent.model")
public record ModelRouteProperties(String name, double temperature, int numCtx, Duration callTimeout,
		NumPredict numPredict) {
}
