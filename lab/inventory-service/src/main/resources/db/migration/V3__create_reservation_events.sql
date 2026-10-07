-- Append-only record of every reservation attempt, whatever its outcome. The application only inserts and reads these
-- rows; nothing updates or deletes them. outcome is RESERVED or INSUFFICIENT_STOCK; detail holds the requested lines as
-- compact JSON; duration_ms is how long the reservation took.
CREATE TABLE reservation_events (
    id          UUID        PRIMARY KEY,
    order_id    UUID        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    type        VARCHAR(64) NOT NULL
        CHECK (type IN ('RESERVATION_ATTEMPT')),
    outcome     VARCHAR(64),
    detail      TEXT,
    duration_ms BIGINT
);

CREATE INDEX reservation_events_order_id_idx ON reservation_events (order_id);
CREATE INDEX reservation_events_occurred_at_idx ON reservation_events (occurred_at);
