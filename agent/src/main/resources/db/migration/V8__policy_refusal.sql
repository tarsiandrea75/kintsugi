-- The policy gate's refusal (agent spec, "The policy authorizes every tool call"; ADR-027): a call whose tool the risk
-- table classifies as a write, or does not name, is recorded as REFUSED_POLICY and never made. The constraint keeps
-- the name PostgreSQL gave it in V1.
ALTER TABLE incident_steps DROP CONSTRAINT incident_steps_tool_outcome_check;
ALTER TABLE incident_steps ADD CONSTRAINT incident_steps_tool_outcome_check
    CHECK (tool_outcome IN ('OK', 'INVALID_PARAMETER', 'UPSTREAM_ERROR', 'CLIENT_ERROR', 'REFUSED_PHASE',
                            'REFUSED_BUDGET', 'REFUSED_PLACEHOLDER', 'REFUSED_DUPLICATE', 'REFUSED_POLICY'));
