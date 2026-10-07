-- Append-only record of every step of an injection's life: activation started, activated, activation failed,
-- restored, restore failed. The application only inserts and reads these rows; nothing updates or deletes them.
-- detail is compact JSON: the exception class and message of a failed step, and the outcome of a compensating restore.
-- The identity id gives the order the events were appended in, even when two share a timestamp.
CREATE TABLE injection_events (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    injection_id UUID         NOT NULL REFERENCES injections (id),
    occurred_at  TIMESTAMPTZ  NOT NULL,
    type         VARCHAR(32)  NOT NULL
        CHECK (type IN ('ACTIVATION_STARTED', 'ACTIVATED', 'ACTIVATION_FAILED', 'RESTORED', 'RESTORE_FAILED')),
    detail       TEXT
);

CREATE INDEX injection_events_injection_id_idx ON injection_events (injection_id);
