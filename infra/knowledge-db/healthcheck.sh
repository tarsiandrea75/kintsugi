#!/bin/sh
# Health check of the knowledge store, for its compose service (T5 of odd/tasks/runbook-rag.md; ADR-002).
#
# pg_isready alone is not enough: when init/01-knowledge.sh stops halfway, the volume is already initialized, the next
# start skips the init scripts, and the server accepts connections without the store's roles or schema. This check
# passes only when kintsugi_agent can read index_metadata, which is the last grant the init script makes. It connects
# over TCP on loopback, so it also fails while the entrypoint's temporary init server (socket only) is running.
set -eu

result=$(psql --host 127.0.0.1 --username "$POSTGRES_USER" --dbname knowledge --no-align --tuples-only \
  --command "SELECT has_table_privilege('kintsugi_agent', 'index_metadata', 'SELECT')")
[ "$result" = "t" ]
