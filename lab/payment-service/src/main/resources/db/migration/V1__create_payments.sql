-- Payment of one order (Spring Data JDBC mapping). No amount yet: orders have no prices until F6.
-- No unique constraint on order_id: there is no idempotency, so two calls for one order store two payments.
CREATE TABLE payments (
    id         UUID        PRIMARY KEY,
    order_id   UUID        NOT NULL,
    status     VARCHAR(64) NOT NULL
        CHECK (status IN ('AUTHORIZED', 'DECLINED')),
    version    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
