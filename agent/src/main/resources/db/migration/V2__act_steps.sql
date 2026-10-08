-- A model answer in an evidence phase gets its own step, kind ACT, whether or not it requested tool calls: every
-- model call is then one row (PLAN, ACT or REPORT), and the tool calls it requested follow as TOOL_CALL rows
-- (agent spec, "State tables"). The constraint keeps the name PostgreSQL gave it in V1.
ALTER TABLE incident_steps DROP CONSTRAINT incident_steps_kind_check;
ALTER TABLE incident_steps ADD CONSTRAINT incident_steps_kind_check
    CHECK (kind IN ('PLAN', 'ACT', 'TOOL_CALL', 'REPORT', 'BUDGET'));
