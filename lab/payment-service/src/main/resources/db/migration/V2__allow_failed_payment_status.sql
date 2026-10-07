-- Fault F2: a gateway 429 is stored as a FAILED payment. V1 is not edited, because existing databases (for example
-- Compose volumes) have already applied it; this migration widens its CHECK constraint instead.
-- payments_status_check is the name PostgreSQL gave V1's unnamed column constraint.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('AUTHORIZED', 'DECLINED', 'FAILED'));
