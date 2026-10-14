-- Two more refusals (agent spec, "Phases and their tools"; T5c9): a call whose argument is a placeholder such as
-- "<SELECT_A_PENDING_ORDER_ID>", and a call with the same tool and arguments as one that already ran in the incident.
-- Neither runs. The constraint keeps the name PostgreSQL gave it in V1.
ALTER TABLE incident_steps DROP CONSTRAINT incident_steps_tool_outcome_check;
ALTER TABLE incident_steps ADD CONSTRAINT incident_steps_tool_outcome_check
    CHECK (tool_outcome IN ('OK', 'INVALID_PARAMETER', 'UPSTREAM_ERROR', 'CLIENT_ERROR', 'REFUSED_PHASE',
                            'REFUSED_BUDGET', 'REFUSED_PLACEHOLDER', 'REFUSED_DUPLICATE'));
