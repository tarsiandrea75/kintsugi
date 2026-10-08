package dev.kintsugi.agent.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@code RUNNING} incident as the loop starts it.
 *
 * @param id the incident's id
 * @param symptom the trimmed symptom
 * @param sinceMinutes the default window offered to the model
 * @param startedAt when the incident was opened; its 10-minute budget runs from here
 */
public record OpenIncident(UUID id, String symptom, int sinceMinutes, Instant startedAt) {
}
