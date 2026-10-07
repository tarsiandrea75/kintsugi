#!/usr/bin/env bash
# Roles and databases of the lab's single PostgreSQL instance (ADR-007, ADR-009).
#
# Runs once, on the first start with an empty data volume, as the bootstrap superuser (POSTGRES_USER, "postgres").
# Only this script uses that superuser. A superuser ignores REVOKE CONNECT, so every other role is NOSUPERUSER:
# - lab_app owns orders, payments and inventory; the four services connect as lab_app.
# - faults owns the faults database, where the fault injector records the true cause of each injection. CONNECT on
#   faults is revoked from PUBLIC, so lab_app (and any later role) cannot even connect to it.
# The passwords are lab defaults set in infra/compose.yaml, overridable in infra/.env (see infra/.env.example).
# Changing them later needs "docker compose -f infra/compose.yaml down -v": this script never runs again on a used volume.
set -euo pipefail

: "${LAB_APP_DB_PASSWORD:?LAB_APP_DB_PASSWORD must be set}"
: "${FAULTS_DB_PASSWORD:?FAULTS_DB_PASSWORD must be set}"

# psql variables (:'name') quote the passwords as SQL literals, so no value is ever spliced into the SQL text.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v lab_app_password="$LAB_APP_DB_PASSWORD" -v faults_password="$FAULTS_DB_PASSWORD" <<'SQL'
CREATE ROLE lab_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'lab_app_password';
CREATE ROLE faults LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'faults_password';

CREATE DATABASE orders OWNER lab_app;
CREATE DATABASE payments OWNER lab_app;
CREATE DATABASE inventory OWNER lab_app;

CREATE DATABASE faults OWNER faults;
REVOKE CONNECT, TEMPORARY ON DATABASE faults FROM PUBLIC;
SQL
