-- Lab seed: enough stock for tests and verify runs, since reservations are never released.
INSERT INTO stock (sku, available) VALUES
    ('SKU-1', 1000000),
    ('SKU-2', 1000000),
    ('SKU-3', 1000000),
    ('SKU-4', 1000000),
    ('SKU-5', 1000000);
