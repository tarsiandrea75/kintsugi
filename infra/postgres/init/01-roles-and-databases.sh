#!/usr/bin/env bash
# Roles and databases of the lab's single PostgreSQL instance (ADR-007, ADR-009, ADR-004).
#
# Runs once, on the first start with an empty data volume, as the bootstrap superuser (POSTGRES_USER, "postgres").
# Only this script uses that superuser. A superuser ignores REVOKE CONNECT, so every other role is NOSUPERUSER:
# - lab_app owns orders, payments and inventory; the four services connect as lab_app.
# - faults owns the faults database, where the fault injector records the true cause of each injection. CONNECT on
#   faults is revoked from PUBLIC, so lab_app (and any later role) cannot even connect to it.
# - kintsugi_agent is the read-only role of mcp-tools, the agent's only way into the databases (ADR-004). It may
#   connect to orders, payments and inventory, and read only the six evidence tables listed below.
# The passwords are lab defaults set in infra/compose.yaml, overridable in infra/.env (see infra/.env.example).
# Changing them later needs "docker compose -f infra/compose.yaml down -v": this script never runs again on a used volume.
set -euo pipefail

: "${LAB_APP_DB_PASSWORD:?LAB_APP_DB_PASSWORD must be set}"
: "${FAULTS_DB_PASSWORD:?FAULTS_DB_PASSWORD must be set}"
# Lab default until compose passes it; the role can only read evidence the agent is meant to read.
KINTSUGI_AGENT_DB_PASSWORD="${KINTSUGI_AGENT_DB_PASSWORD:-kintsugi_agent}"

# psql variables (:'name') quote the passwords as SQL literals, so no value is ever spliced into the SQL text.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v lab_app_password="$LAB_APP_DB_PASSWORD" -v faults_password="$FAULTS_DB_PASSWORD" \
  -v kintsugi_agent_password="$KINTSUGI_AGENT_DB_PASSWORD" <<'SQL'
CREATE ROLE lab_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'lab_app_password';
CREATE ROLE faults LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'faults_password';
CREATE ROLE kintsugi_agent LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'kintsugi_agent_password';
-- Session defaults of every kintsugi_agent connection: no writes, and no statement, transaction or idle transaction
-- that can hold the lab's database for long. They are defaults, not locks: what stops writes is that the role has no
-- grant other than SELECT.
ALTER ROLE kintsugi_agent SET default_transaction_read_only = on;
ALTER ROLE kintsugi_agent SET statement_timeout = '3s';
ALTER ROLE kintsugi_agent SET transaction_timeout = '5s';
ALTER ROLE kintsugi_agent SET idle_in_transaction_session_timeout = '5s';

CREATE DATABASE orders OWNER lab_app;
CREATE DATABASE payments OWNER lab_app;
CREATE DATABASE inventory OWNER lab_app;
GRANT CONNECT ON DATABASE orders, payments, inventory TO kintsugi_agent;

CREATE DATABASE faults OWNER faults;
REVOKE CONNECT, TEMPORARY ON DATABASE faults FROM PUBLIC;
SQL

# The evidence tables do not exist yet: each service's Flyway creates them later, as lab_app. A GRANT now has nothing
# to apply to, and "ALTER DEFAULT PRIVILEGES" cannot name tables, so it would also open order_items,
# flyway_schema_history and any table a later migration adds. Instead, an event trigger in each database grants SELECT
# to kintsugi_agent when a table with one of the six allowed names is created in schema public, and does nothing for any
# other table (ADR-004).
# - The grant runs as the role that created the table, lab_app, which owns it and so may grant on it.
# - The trigger function lives in its own schema, owned by the superuser and closed to PUBLIC, so schema public stays
#   empty for Flyway and no other role can change the function.
for db in orders payments inventory; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<'SQL'
GRANT USAGE ON SCHEMA public TO kintsugi_agent;

CREATE SCHEMA kintsugi_agent_grants;
REVOKE ALL ON SCHEMA kintsugi_agent_grants FROM PUBLIC;

CREATE FUNCTION kintsugi_agent_grants.grant_select_on_evidence_tables() RETURNS event_trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
DECLARE
  created record;
BEGIN
  FOR created IN
    SELECT object_identity
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag = 'CREATE TABLE'
      AND object_type = 'table'
      AND schema_name = 'public'
      AND object_identity IN ('public.orders', 'public.order_events', 'public.payments', 'public.payment_events',
                              'public.stock', 'public.reservation_events')
  LOOP
    EXECUTE format('GRANT SELECT ON TABLE %s TO kintsugi_agent', created.object_identity);
  END LOOP;
END
$$;

CREATE EVENT TRIGGER kintsugi_agent_grant_select ON ddl_command_end
  WHEN TAG IN ('CREATE TABLE')
  EXECUTE FUNCTION kintsugi_agent_grants.grant_select_on_evidence_tables();
SQL
done
