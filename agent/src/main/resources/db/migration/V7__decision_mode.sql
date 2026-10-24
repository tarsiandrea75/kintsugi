-- Decision mode (agent spec, "Decision mode"; odd/tasks/decision-instrument.md T1): the loop records it on the
-- incident like the other two modes. The constraint keeps the name PostgreSQL gave it in V4.
ALTER TABLE incidents DROP CONSTRAINT incidents_mode_check;
ALTER TABLE incidents ADD CONSTRAINT incidents_mode_check CHECK (mode IN ('explorer', 'dossier', 'decision'));
