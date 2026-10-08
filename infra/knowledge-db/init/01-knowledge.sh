#!/usr/bin/env bash
# Database, extension, roles and schema of the knowledge store, the pgvector container that holds the agent's runbook
# index (ADR-002, openspec/specs/knowledge-base/spec.md). It is a server of its own, separate from the lab's postgres.
#
# Runs once, on the first start with an empty data volume, as the bootstrap superuser (POSTGRES_USER). Only this script
# uses that superuser. A superuser ignores REVOKE CONNECT, so every other role is NOSUPERUSER:
# - knowledge_indexer owns the two tables and writes them; only the one-shot indexer (the knowledge/ module) uses it.
# - kintsugi_agent is mcp-tools' read-only role: CONNECT on knowledge and SELECT on the two tables, nothing else. Same
#   name as on the lab's server, but a different server with its own password.
# The extension and the schema are created here, not by the indexer or Spring AI: "vector" is not a trusted extension,
# so only a superuser may create it, and keeping all DDL here keeps both application roles unprivileged.
# The passwords come from the environment of the container. Changing them later needs the volume removed: this script
# never runs again on a used volume.
set -euo pipefail

: "${KNOWLEDGE_INDEXER_DB_PASSWORD:?KNOWLEDGE_INDEXER_DB_PASSWORD must be set}"
: "${KINTSUGI_AGENT_DB_PASSWORD:?KINTSUGI_AGENT_DB_PASSWORD must be set}"

# psql variables (:'name') quote the passwords as SQL literals, so no value is ever spliced into the SQL text.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v indexer_password="$KNOWLEDGE_INDEXER_DB_PASSWORD" -v agent_password="$KINTSUGI_AGENT_DB_PASSWORD" <<'SQL'
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

-- The database stays owned by the superuser, so neither role can change its settings or grants.
CREATE DATABASE knowledge;
REVOKE CONNECT, TEMPORARY ON DATABASE knowledge FROM PUBLIC;
GRANT CONNECT ON DATABASE knowledge TO knowledge_indexer, kintsugi_agent;
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

ALTER TABLE runbook_chunks OWNER TO knowledge_indexer;
ALTER TABLE index_metadata OWNER TO knowledge_indexer;

GRANT USAGE ON SCHEMA public TO knowledge_indexer, kintsugi_agent;
GRANT SELECT ON runbook_chunks, index_metadata TO kintsugi_agent;
SQL
