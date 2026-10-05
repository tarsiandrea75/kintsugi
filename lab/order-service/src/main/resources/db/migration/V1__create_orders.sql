-- Order aggregate: one row in orders, its items in order_items (Spring Data JDBC mapping).
-- Edited in place to add the VARCHAR(64) limits: acceptable only because no environment has applied V1 yet.
-- From the first shared deployment on, schema changes go in new migrations.
CREATE TABLE orders (
    id          UUID        PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    status      TEXT        NOT NULL
        CHECK (status IN ('CREATED', 'PAYMENT_PENDING', 'PAID', 'PAYMENT_FAILED')),
    version     BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE order_items (
    order_id   UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    item_index INTEGER     NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    quantity   INTEGER     NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, item_index)
);
