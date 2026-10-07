-- Available units per SKU. The check keeps stock from ever going negative, even if a caller skips the
-- conditional update that reservations use.
CREATE TABLE stock (
    sku       VARCHAR(64) PRIMARY KEY,
    available INT         NOT NULL CHECK (available >= 0)
);
