-- The agent's state (agent spec, "State tables"; ADR-010). Run by Flyway as agent_app, which owns both tables.

-- One row per run. signal holds the request ({"symptom", "sinceMinutes"}); report holds the validated report.
CREATE TABLE incidents (
    id             UUID        PRIMARY KEY,
    signal         JSONB       NOT NULL,
    status         TEXT        NOT NULL
        CHECK (status IN ('RUNNING', 'COMPLETED', 'INVALID_REPORT', 'BUDGET_EXCEEDED', 'TIMED_OUT', 'FAILED')),
    report         JSONB,
    failure_reason TEXT,
    started_at     TIMESTAMPTZ NOT NULL,
    ended_at       TIMESTAMPTZ,
    -- Every end records ended_at; a running incident has none.
    CONSTRAINT incidents_ended_at_check CHECK ((status = 'RUNNING') = (ended_at IS NULL)),
    -- Every end but COMPLETED records its reason; a running or completed incident has none.
    CONSTRAINT incidents_failure_reason_check
        CHECK ((status IN ('RUNNING', 'COMPLETED')) = (failure_reason IS NULL)),
    -- A completed incident has a report, and only a completed one.
    CONSTRAINT incidents_report_check CHECK ((status = 'COMPLETED') = (report IS NOT NULL))
);

-- One incident at a time, enforced atomically: at most one RUNNING row, whatever the number of concurrent inserts.
-- The constant expression makes every RUNNING row collide with every other (the lab's pattern for one blocking
-- row). The API inserts without checking first and answers 409 on the unique violation.
CREATE UNIQUE INDEX incidents_one_running_idx ON incidents ((true)) WHERE status = 'RUNNING';

-- One row per model call or tool call, written when the step ends. tool_output is the full output returned by
-- mcp-tools; summary is what the model saw; model_output is the raw plan or report answer.
CREATE TABLE incident_steps (
    incident_id  UUID        NOT NULL REFERENCES incidents (id),
    step_no      INTEGER     NOT NULL CHECK (step_no >= 1),
    kind         TEXT        NOT NULL CHECK (kind IN ('PLAN', 'TOOL_CALL', 'REPORT', 'BUDGET')),
    phase        TEXT        NOT NULL CHECK (phase IN ('TRIAGE', 'INVESTIGATE', 'KNOWLEDGE', 'CONCLUDE')),
    model        TEXT,
    tokens       INTEGER,
    tool_name    TEXT,
    tool_args    JSONB,
    tool_output  TEXT,
    tool_outcome TEXT
        CHECK (tool_outcome IN ('OK', 'INVALID_PARAMETER', 'UPSTREAM_ERROR', 'CLIENT_ERROR', 'REFUSED_PHASE',
                                'REFUSED_BUDGET')),
    summary      TEXT,
    model_output TEXT,
    latency_ms   INTEGER,
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (incident_id, step_no)
);
