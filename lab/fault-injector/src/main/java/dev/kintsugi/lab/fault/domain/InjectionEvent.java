package dev.kintsugi.lab.fault.domain;

import java.time.Instant;
import java.util.UUID;

/** One append-only row of {@code injection_events}. {@code detail} is compact JSON, or null. */
public record InjectionEvent(long id, UUID injectionId, InjectionEventType type, Instant occurredAt, String detail) {

}
