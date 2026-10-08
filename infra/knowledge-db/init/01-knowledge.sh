#!/usr/bin/env bash
# Database, extension, roles and schema of the knowledge store, the pgvector container that holds the agent's runbook
# index (ADR-002, openspec/specs/knowledge-base/spec.md). It is a server of its own, separate from the lab's postgres.
# It also hosts the agent's state, the agent database (ADR-010, openspec/specs/agent/spec.md).
#
# Runs once, on the first start with an empty data volume, as the bootstrap superuser (POSTGRES_USER). Only this script
# uses that superuser. A superuser ignores REVOKE CONNECT, so every other role is NOSUPERUSER:
# - knowledge_indexer owns the three tables and writes them; only the one-shot indexer (the knowledge/ module) uses it.
# - kintsugi_agent is mcp-tools' read-only role: CONNECT on knowledge and SELECT on runbook_chunks and index_metadata,
#   nothing else (not even indexed_files, the indexer's own bookkeeping). Same name as on the lab's server, but a
#   different server with its own password.
# - agent_app is the agent's role on its own database, agent, and on nothing else: no CONNECT on knowledge (runbooks
#   come through mcp-tools' search_runbooks), and neither knowledge role may connect to agent. The agent's Flyway
#   creates its tables as agent_app, so this script grants it CREATE on agent's schema public only; the database itself
#   stays the superuser's, so agent_app cannot grant CONNECT to another role, change the database's settings or create
#   another schema, as an owner could.
# The extension and the schema are created here, not by the indexer or Spring AI: "vector" is not a trusted extension,
# so only a superuser may create it, and keeping all DDL here keeps both application roles unprivileged.
# The passwords come from the environment of the container. Changing them later needs the volume removed: this script
# never runs again on a used volume.
#
# A failed init is not atomic and needs the volume removed. The postgres entrypoint runs initdb before this script, so
# when the script stops halfway the volume is already initialized: on the next start the entrypoint skips every init
# script and serves a store without its roles or schema. The fix is the same as for the lab's postgres: fix the cause,
# then `docker compose down -v` for the store's volume and start again. Two things keep this rare and visible:
# - the required variables are checked below, before any SQL runs, so a missing password leaves no half-made store;
# - the grant to kintsugi_agent on index_metadata is the last statement, and ../healthcheck.sh reports healthy only
#   once it exists, so a store whose init stopped halfway never reports healthy.
set -euo pipefail

: "${KNOWLEDGE_INDEXER_DB_PASSWORD:?KNOWLEDGE_INDEXER_DB_PASSWORD must be set}"
: "${KINTSUGI_AGENT_DB_PASSWORD:?KINTSUGI_AGENT_DB_PASSWORD must be set}"
: "${AGENT_DB_PASSWORD:?AGENT_DB_PASSWORD must be set}"

# psql reads the passwords from the environment itself (\getenv), so they never appear on a command line, and the
# :'name' form quotes them as SQL literals, so no value is ever spliced into the SQL text.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv indexer_password KNOWLEDGE_INDEXER_DB_PASSWORD
\getenv agent_password KINTSUGI_AGENT_DB_PASSWORD
\getenv agent_app_password AGENT_DB_PASSWORD
CREATE ROLE knowledge_indexer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'indexer_password';
CREATE ROLE kintsugi_agent LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'agent_password';
-- Session defaults of every kintsugi_agent connection, as on the lab's server: no writes, and no statement, transaction
-- or idle transaction that can hold the store for long. They are defaults, not locks: what stops writes is that the
-- role has no grant other than SELECT.
ALTER ROLE kintsugi_agent SET default_transaction_read_only = on;
ALTER ROLE kintsugi_agent SET statement_timeout = '3s';
ALTER ROLE kintsugi_agent SET transaction_timeout = '5s';
ALTER ROLE kintsugi_agent SET idle_in_transaction_session_timeout = '5s';
CREATE ROLE agent_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'agent_app_password';

-- The database stays owned by the superuser, so neither role can change its settings or grants.
CREATE DATABASE knowledge;
REVOKE CONNECT, TEMPORARY ON DATABASE knowledge FROM PUBLIC;
GRANT CONNECT ON DATABASE knowledge TO knowledge_indexer, kintsugi_agent;

-- The agent's state: incidents and their steps. Also owned by the superuser, for the same reason; only agent_app may
-- connect.
CREATE DATABASE agent;
REVOKE CONNECT, TEMPORARY ON DATABASE agent FROM PUBLIC;
GRANT CONNECT ON DATABASE agent TO agent_app;
SQL

# The agent's schema is not created here: its own Flyway migrations (agent/src/main/resources/db/migration) create it,
# as agent_app, which then owns the tables. PUBLIC has no CREATE on schema public (PostgreSQL 15 and later), so this
# grant is what lets agent_app create them, in this database only. It runs before the knowledge schema below, so the
# knowledge grant to kintsugi_agent stays the last statement, the mark ../healthcheck.sh looks for.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname agent <<'SQL'
GRANT USAGE, CREATE ON SCHEMA public TO agent_app;
SQL

# The schema, in database knowledge. Tables are created by the superuser, then handed to knowledge_indexer, so the
# indexer owns them (and their indexes) without holding CREATE on schema public; PUBLIC has no CREATE there either
# (PostgreSQL 15 and later), so kintsugi_agent cannot create tables.
#
# The dimension 768 is fixed, not a variable: ADR-002 fixes nomic-embed-text and its 768 dimensions, and pgvector
# checks every inserted vector against the column's dimension. A model with other dimensions needs a migration of this
# column and a full re-index, not a configuration change.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname knowledge <<'SQL'
CREATE EXTENSION vector;

-- One row per chunk, in the layout Spring AI's PgVectorStore reads. metadata holds service (absent for README.md),
-- section, source and content_hash (knowledge-base spec, "Chunks carry their metadata").
CREATE TABLE runbook_chunks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  content text NOT NULL,
  metadata json NOT NULL,
  embedding vector(768) NOT NULL
);
-- HNSW needs no training step, so it works on an empty table and after a re-index; cosine is Nomic's distance.
CREATE INDEX runbook_chunks_embedding_hnsw ON runbook_chunks USING hnsw (embedding vector_cosine_ops);

-- The model the whole index was built with: exactly one row, written in the same transaction as the chunks.
CREATE TABLE index_metadata (
  id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  model_name text NOT NULL,
  model_digest text NOT NULL,
  dimensions integer NOT NULL,
  document_prefix text NOT NULL,
  query_prefix text NOT NULL,
  indexed_at timestamptz NOT NULL DEFAULT now()
);

-- The content hash of every file the last run indexed, one row per file, including a file that yields no chunk (a
-- runbook with only a title). Without it such a file would have no row to compare with and be re-read on every run.
-- Written in the same transaction as the chunks. Only the indexer reads it: kintsugi_agent gets no grant on it.
CREATE TABLE indexed_files (
  source text PRIMARY KEY,
  content_hash text NOT NULL
);

ALTER TABLE runbook_chunks OWNER TO knowledge_indexer;
ALTER TABLE index_metadata OWNER TO knowledge_indexer;
ALTER TABLE indexed_files OWNER TO knowledge_indexer;

GRANT USAGE ON SCHEMA public TO knowledge_indexer, kintsugi_agent;
GRANT SELECT ON runbook_chunks TO kintsugi_agent;
-- Last on purpose: ../healthcheck.sh looks for this grant, so it is the mark of a complete init.
GRANT SELECT ON index_metadata TO kintsugi_agent;
SQL
