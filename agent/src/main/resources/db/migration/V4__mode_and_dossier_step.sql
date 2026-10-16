-- Dossier mode (agent spec, "Dossier mode"; ADR-010, "Amended in T5a"). The loop records its mode on the incident
-- when the run starts, so the evals can split the results; every earlier run was an explorer run. An incident whose
-- run never started has no mode.
ALTER TABLE incidents ADD COLUMN mode TEXT CHECK (mode IN ('explorer', 'dossier'));
UPDATE incidents SET mode = 'explorer';

-- The dossier is one step of its own, kind DOSSIER in phase DOSSIER, before the first evidence phase: summary holds
-- the dossier message the model saw, tool_output the dossier's calls as JSON. The constraints keep the names
-- PostgreSQL gave them in V1.
ALTER TABLE incident_steps DROP CONSTRAINT incident_steps_kind_check;
ALTER TABLE incident_steps ADD CONSTRAINT incident_steps_kind_check
    CHECK (kind IN ('PLAN', 'ACT', 'TOOL_CALL', 'REPORT', 'BUDGET', 'DOSSIER'));
ALTER TABLE incident_steps DROP CONSTRAINT incident_steps_phase_check;
ALTER TABLE incident_steps ADD CONSTRAINT incident_steps_phase_check
    CHECK (phase IN ('DOSSIER', 'TRIAGE', 'INVESTIGATE', 'KNOWLEDGE', 'CONCLUDE'));
