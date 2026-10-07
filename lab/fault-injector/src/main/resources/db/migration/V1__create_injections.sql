-- The ground truth of every injection (ADR-009, openspec/specs/fault-injector/spec.md). One row per accepted
-- activation. The truth columns are written once; only status and restored_at ever change.
CREATE TABLE injections (
    id                  UUID         PRIMARY KEY,
    fault_id            VARCHAR(16)  NOT NULL,
    target_service      VARCHAR(64)  NOT NULL,
    root_cause_service  VARCHAR(64)  NOT NULL,
    root_cause_category VARCHAR(64)  NOT NULL,
    root_cause          TEXT         NOT NULL,
    parameters          JSONB        NOT NULL,
    injected_at         TIMESTAMPTZ  NOT NULL,
    restored_at         TIMESTAMPTZ,
    status              VARCHAR(32)  NOT NULL
        CHECK (status IN ('ACTIVE', 'RESTORE_PENDING', 'RESTORED', 'FAILED'))
);

-- At most one blocking injection (ACTIVE or RESTORE_PENDING), so each incident has exactly one true cause. The index is
-- on a constant: an index on status would still admit one ACTIVE and one RESTORE_PENDING row. Two concurrent inserts
-- of a blocking row end with one unique violation, which the API answers with 409.
CREATE UNIQUE INDEX injections_one_blocking_idx ON injections ((true))
    WHERE status IN ('ACTIVE', 'RESTORE_PENDING');

CREATE INDEX injections_injected_at_idx ON injections (injected_at);
