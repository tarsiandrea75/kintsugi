-- Append-only record of every payment attempt, whatever its outcome, including a gateway that gave no usable answer
-- (no payments row exists then). The application only inserts and reads these rows; nothing updates or deletes them.
-- outcome is AUTHORIZED, DECLINED, FAILED or GATEWAY_UNAVAILABLE; detail is compact JSON (the payment id, or the
-- exception class, URL and message); duration_ms is how long the attempt took, gateway call included.
CREATE TABLE payment_events (
    id          UUID        PRIMARY KEY,
    order_id    UUID        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    type        VARCHAR(64) NOT NULL
        CHECK (type IN ('PAYMENT_ATTEMPT')),
    outcome     VARCHAR(64),
    detail      TEXT,
    duration_ms BIGINT
);

CREATE INDEX payment_events_order_id_idx ON payment_events (order_id);
CREATE INDEX payment_events_occurred_at_idx ON payment_events (occurred_at);
