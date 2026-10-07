-- Append-only record of everything that happened to an order: received, the inventory answer, the payment answer.
-- The application only inserts and reads these rows; nothing updates or deletes them. outcome is the order status
-- after the event; detail is compact JSON (the order's customer and items when received, or the exception class, URL
-- and message of an unavailable dependency); duration_ms is how long the call to the other service took.
-- No foreign key to orders: the event log must never be removed together with an order.
CREATE TABLE order_events (
    id          UUID        PRIMARY KEY,
    order_id    UUID        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    type        VARCHAR(64) NOT NULL
        CHECK (type IN ('ORDER_RECEIVED', 'INVENTORY_RESERVED', 'INVENTORY_REJECTED', 'INVENTORY_UNAVAILABLE',
                        'PAYMENT_AUTHORIZED', 'PAYMENT_DECLINED', 'PAYMENT_FAILED', 'PAYMENT_UNAVAILABLE')),
    outcome     VARCHAR(64),
    detail      TEXT,
    duration_ms BIGINT
);

CREATE INDEX order_events_order_id_idx ON order_events (order_id);
CREATE INDEX order_events_occurred_at_idx ON order_events (occurred_at);
