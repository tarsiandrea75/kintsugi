-- Token usage on every model step (agent spec, "Token usage"; feature cloud-route, T2). An ACT or REPORT step records
-- the prompt and completion tokens its provider reported for the call, so the evals measure what reading the prompt
-- and writing the answer cost on either route, instead of inferring it from the latency. Null when the provider
-- reported no usage, and on every other kind of step; earlier runs recorded only the total, in tokens.
ALTER TABLE incident_steps ADD COLUMN prompt_tokens INT;
ALTER TABLE incident_steps ADD COLUMN completion_tokens INT;
