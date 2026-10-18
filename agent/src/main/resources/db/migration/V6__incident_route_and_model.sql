-- The cloud route (agent spec, "Routes"; ADR-012; ADR-010, "Amended in cloud-route T3"). The loop records its route
-- and the route's model on the incident when the run starts, like the mode, so the evals can split the results. An
-- incident whose run never started has neither.
ALTER TABLE incidents ADD COLUMN route TEXT CHECK (route IN ('local', 'cloud'));
ALTER TABLE incidents ADD COLUMN model TEXT;

-- Every earlier run was local; its model is the one its steps name.
UPDATE incidents SET route = 'local' WHERE mode IS NOT NULL;
UPDATE incidents SET model = (SELECT s.model FROM incident_steps s
                              WHERE s.incident_id = incidents.id AND s.model IS NOT NULL
                              ORDER BY s.step_no LIMIT 1)
WHERE mode IS NOT NULL;
