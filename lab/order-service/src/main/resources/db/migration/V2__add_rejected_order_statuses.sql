-- Rejected orders are stored too: REJECTED_OUT_OF_STOCK when inventory-service answered 409, and
-- REJECTED_INVENTORY_UNAVAILABLE when it gave no usable answer. Both are terminal and reached only from CREATED.
-- V1 is not edited: the inline CHECK it created (named orders_status_check by PostgreSQL) is replaced here.
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('CREATED', 'PAYMENT_PENDING', 'PAID', 'PAYMENT_FAILED', 'REJECTED_OUT_OF_STOCK',
                      'REJECTED_INVENTORY_UNAVAILABLE'));
