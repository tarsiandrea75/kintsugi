# Knowledge Base Specification

## Purpose

The knowledge base is what the agent searches to learn what a symptom means and where to look: the service runbooks in `docs/runbooks/`, split into sections and embedded for semantic search. It has two parts: a pgvector store holding the `knowledge` database, and a one-shot indexer, the `knowledge/` module, that fills it. mcp-tools reads it through `search_runbooks` ([mcp-tools spec](../mcp-tools/spec.md)). Design: [feature document](../../../odd/tasks/runbook-rag.md) and [ADR-002](../../../docs/adr/ADR-002-pgvector-knowledge-base.md).

## Shared rules

| Rule | Value |
|------|-------|
| Store image | `pgvector/pgvector:0.8.7-pg18-trixie`, pinned by digest; a container separate from the lab's `postgres` |
| Database | `knowledge`, with `CONNECT` revoked from `PUBLIC`. The same instance also hosts the agent's state, database `agent` with its own role `agent_app`, which has no `CONNECT` on `knowledge`, while neither knowledge role may connect to `agent` ([agent spec](../agent/spec.md), ADR-010). Adding `agent` to a store made before it needs `docker compose down -v` for the store and a re-index |
| Extension | `vector`, created by the init script as the bootstrap superuser (it is not a trusted extension) |
| Init script | `infra/knowledge-db/init/01-knowledge.sh`, run once on an empty volume as the bootstrap superuser; tested by `mcp-tools`' `KnowledgeStoreRoleTest` on the pinned image. It is not atomic: initdb runs before it, so a failed init leaves an initialized but incomplete volume that later starts skip; the recovery is `docker compose down -v` for the store, as for the lab's postgres |
| Health check | `infra/knowledge-db/healthcheck.sh`: healthy only when `kintsugi_agent` can read `index_metadata`, the init script's last grant, so an incomplete init never reports healthy. It connects as `kintsugi_agent` over loopback (trusted by the image's `pg_hba.conf`), so it does not depend on `POSTGRES_USER` being set in the `docker exec` environment |
| Roles | `knowledge_indexer` (owns and writes the tables; password from `KNOWLEDGE_INDEXER_DB_PASSWORD`), `kintsugi_agent` (reads only; password from `KINTSUGI_AGENT_DB_PASSWORD`, set separately from the lab server's), and the agent's `agent_app` (password from `AGENT_DB_PASSWORD`; see the agent spec). All three are required: the init script stops without them, before any SQL |
| Agent session defaults | `default_transaction_read_only=on`, `statement_timeout=3s`, `transaction_timeout=5s`, `idle_in_transaction_session_timeout=5s`, as on the lab's server |
| Chunk table | `runbook_chunks`: `id uuid` (primary key), `content text`, `metadata json` (holding `service`, `section`, `source`, `content_hash`), `embedding vector(768)`; all `NOT NULL` |
| File table | `indexed_files`: `source text` (primary key), `content_hash text NOT NULL`; one row per file the last run indexed, including a file that yields no chunk. Owned and read by `knowledge_indexer` only: `kintsugi_agent` has no grant on it. Added after the first volumes were made: a store created before it needs `docker compose down -v` and a fresh init |
| Index metadata table | `index_metadata`, exactly one row (`id` 1): `model_name`, `model_digest`, `dimensions`, `document_prefix`, `query_prefix`, `indexed_at` |
| Index | `runbook_chunks_embedding_hnsw`: HNSW, cosine distance (`vector_cosine_ops`), on `embedding`. The column's 768 is fixed in the schema: a model with other dimensions needs a migration and a full re-index |
| Schema owner | the init script; Spring AI never creates it: no module uses `PgVectorStore`. The indexer writes and `search_runbooks` reads with plain SQL |
| Embedding model | configurable: name (default `nomic-embed-text`), dimensions (default 768), document prefix (default `search_document: `), query prefix (default `search_query: `) |
| Model digest | read from Ollama, never configured: `GET /api/tags`, the entry whose `name` equals the configured model; a configured name without a tag means `:latest` (`nomic-embed-text` is matched as `nomic-embed-text:latest`) |
| Chunk size | at most 800 tokens of text per chunk, counted with `TokenTextSplitter`'s tokenizer (cl100k_base) |
| Indexed files | `docs/runbooks/*.md` only, read from a read-only mount of that directory |
| Leakage pattern | `\b(F[1-8]\b\|fault\|inject)`, case-insensitive: the runbook authoring rule's check. A match is a fault id, or a word that starts with `fault` or `inject`; a word that only contains them, such as `default`, does not match |
| Chunk metadata | `service`, `section`, `source`, `content_hash` |
| Compose wiring | services `knowledge-db` (volume `knowledge-data`, no host port; compose passes `KNOWLEDGE_AGENT_DB_PASSWORD` to the init script as `KINTSUGI_AGENT_DB_PASSWORD`), `knowledge-indexer` (one-shot, `restart: "no"`, only `docs/runbooks` mounted read-only at `/runbooks`, after `knowledge-db` is healthy) and `ollama-proxy` (socat on the host network, listening on the Docker bridge gateway only and forwarding to Ollama on `127.0.0.1:11434`; `restart: unless-stopped`, so a failed bind is retried until the address is free). Only the indexer depends on the proxy, optionally: when it is not healthy the indexer runs anyway and exits 1. Containers reach Ollama as `http://host.docker.internal:11434`. mcp-tools waits for the indexer's run as an optional dependency, so a failed run never keeps the evidence tools down. Verified by `infra/scripts/verify-rag.sh` |

## Requirements

### Requirement: The store is isolated from the lab and the truth

The knowledge store MUST run in its own container, separate from the lab's `postgres`, so that a fault on the lab's database does not affect it. It MUST hold no lab data and no copy of the `faults` database, and none of its roles MUST hold credentials for the lab's server.

#### Scenario: Lab database down
- GIVEN the lab's `postgres` container is stopped
- WHEN `kintsugi_agent` connects to the `knowledge` database
- THEN the connection succeeds and the chunks can be read

#### Scenario: No faults database
- WHEN the databases of the knowledge store are listed
- THEN `faults` is not among them

#### Scenario: Closed to PUBLIC
- GIVEN a role with no explicit `CONNECT` grant on `knowledge`
- WHEN it tries to connect to `knowledge`
- THEN the connection is refused for lack of `CONNECT` permission

### Requirement: Roles are unprivileged and the agent reads only

Neither `knowledge_indexer` nor `kintsugi_agent` MUST be a superuser, and neither MUST be able to create databases or roles. `kintsugi_agent` MUST have `CONNECT` on `knowledge` and `SELECT` on `runbook_chunks` and `index_metadata`, and no other privilege (none on `indexed_files`). Only `knowledge_indexer` MUST be able to write the tables.

#### Scenario: Not superusers
- WHEN `SELECT rolname, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname IN ('knowledge_indexer', 'kintsugi_agent')` runs
- THEN both rows have all three columns false

#### Scenario: Agent cannot write
- WHEN `kintsugi_agent` runs `INSERT`, `UPDATE`, `DELETE` or `TRUNCATE` on any table of `knowledge`
- THEN the statement fails for lack of permission

#### Scenario: Agent cannot change the schema
- WHEN `kintsugi_agent` runs `CREATE TABLE` or `DROP INDEX` in `knowledge`
- THEN the statement fails for lack of permission

#### Scenario: Agent reads the chunks
- GIVEN the index holds chunks
- WHEN `kintsugi_agent` selects from `runbook_chunks` and `index_metadata`
- THEN both queries succeed
- AND a query ordered by cosine distance (`<=>`) can use `runbook_chunks_embedding_hnsw`

#### Scenario: Agent session defaults
- WHEN `kintsugi_agent` connects to `knowledge`
- THEN the session starts read-only, with `statement_timeout` 3s, `transaction_timeout` 5s and `idle_in_transaction_session_timeout` 5s

#### Scenario: Wrong dimension
- WHEN `knowledge_indexer` inserts a chunk whose embedding does not have 768 dimensions
- THEN the insert fails

#### Scenario: Incomplete init is not healthy
- GIVEN a store whose init script stopped before its last grant
- WHEN the health check runs
- THEN it fails, while it passes on a store whose init completed

#### Scenario: Health check without POSTGRES_USER
- GIVEN a store whose init completed
- WHEN the health check runs with `POSTGRES_USER` unset in its environment
- THEN it passes

#### Scenario: Extension already present
- GIVEN a fresh volume after the init script ran
- WHEN `SELECT extversion FROM pg_extension WHERE extname = 'vector'` runs in `knowledge`
- THEN the result is `0.8.7`, and neither application role had to create it

### Requirement: Only the runbooks are indexed

The indexer MUST read only files matching `docs/runbooks/*.md`. It MUST NOT read or index any other path, including `openspec/`, `odd/` and subdirectories of `docs/runbooks/`. It MUST see only a read-only mount of `docs/runbooks/`.

#### Scenario: Allowed files
- GIVEN `docs/runbooks/` holds `README.md` and the four service runbooks
- WHEN the indexer runs
- THEN the `source` of every stored chunk is one of those five paths

#### Scenario: Other files ignored
- GIVEN the mounted directory also holds `notes.txt` and `drafts/extra.md`
- WHEN the indexer runs
- THEN no chunk has either as its `source`

#### Scenario: No runbooks
- GIVEN the mounted directory holds no `*.md` file, for example because the mount is missing
- WHEN the indexer runs
- THEN it exits with a non-zero code and the index is unchanged, instead of removing every indexed file

#### Scenario: Read-only source
- WHEN the indexer runs
- THEN it writes nothing under the mounted runbooks directory

### Requirement: The leakage gate stops the run

Before embedding anything, the indexer MUST scan every allowed file for the leakage pattern. The README's own "Authoring rule" section, which states the rule, is exempt from the scan and MUST NOT be indexed. Any other match MUST stop the run with a non-zero exit code, name the file and line, and leave the index unchanged.

#### Scenario: Clean runbooks
- GIVEN no allowed file matches the pattern outside the README's "Authoring rule" section
- WHEN the indexer runs
- THEN it indexes the files and exits with code 0

#### Scenario: A runbook names a fault
- GIVEN `docs/runbooks/order-service.md` contains the line "This is fault F3"
- WHEN the indexer runs
- THEN it exits with a non-zero code, naming `docs/runbooks/order-service.md` and the line number
- AND no embedding is requested and no row is written or deleted

#### Scenario: Case and word forms
- GIVEN a runbook contains "Injected latency" or "FAULT"
- WHEN the indexer runs
- THEN the gate stops the run

#### Scenario: A word that only contains the pattern
- GIVEN a runbook contains "the default pool size" and no other match
- WHEN the indexer runs
- THEN the gate passes, because "default" does not start with `fault`

#### Scenario: Authoring rule is not indexed
- WHEN the indexer completes
- THEN no chunk has `section` "Authoring rule"

### Requirement: Chunks carry their metadata

The indexer MUST split each file by Markdown heading, then split any section longer than the splitter's token limit. A chunk's text is the section's Markdown, starting with its heading line; a heading with no text under it, such as a runbook's title, yields no chunk. A chunk MUST NOT hold more than 800 tokens of text, so a single chunk stays well under mcp-tools' 8 KB output cap in the common case. Every chunk MUST carry:

| Field | Value |
|-------|-------|
| `service` | the file name without `.md` when it is one of the allowed services (`order-service`, `payment-service`, `inventory-service`, `gateway-mock`); absent for `README.md` |
| `section` | the text of the chunk's heading, without Markdown markup (``Slow `POST /reservations` `` gives `Slow POST /reservations`) |
| `source` | the repository-relative path, such as `docs/runbooks/order-service.md` |
| `content_hash` | the SHA-256 of the whole file's content when it was indexed |

#### Scenario: A service runbook section
- WHEN `docs/runbooks/inventory-service.md` is indexed
- THEN each of its chunks has `service` `inventory-service`, `source` `docs/runbooks/inventory-service.md`, the heading of its section as `section`, and the file's `content_hash`

#### Scenario: Long section
- GIVEN a section longer than the splitter's token limit
- WHEN it is indexed
- THEN it becomes several chunks, each with the same `service`, `section` and `source`, and none longer than 800 tokens

### Requirement: Unchanged files are skipped

The indexer MUST compare each file's content hash with the one recorded in `indexed_files` by the last run. An unchanged file MUST NOT be embedded again, including a file that yields no chunk. A changed file MUST have all its old chunks replaced. A file no longer present MUST have its chunks removed. A run that changes nothing MUST say so in its log, with the model, its digest and the skipped files.

#### Scenario: Second run with no changes
- GIVEN the index was built from the current runbooks
- WHEN the indexer runs again
- THEN no embedding is requested and the chunk rows are unchanged
- AND its log reads `Index unchanged, every runbook skipped, with nomic-embed-text (<digest>)` with `embedded=[]`

#### Scenario: A file with no chunks
- GIVEN `docs/runbooks/payment-service.md` holds only its title heading, so it yields no chunk, and the index was built from the current runbooks
- WHEN the indexer runs again
- THEN the file is reported as skipped, no embedding is requested and the index is unchanged

#### Scenario: One file changed
- GIVEN only `docs/runbooks/gateway-mock.md` changed since the last run
- WHEN the indexer runs
- THEN only that file is embedded, its old chunks are gone and its new chunks carry the new `content_hash`

#### Scenario: File removed
- GIVEN a runbook present at the last run is deleted
- WHEN the indexer runs
- THEN no chunk has that `source`

### Requirement: The index records its model

The index metadata table MUST hold the embedding model's name, its digest, the dimensions the chunks were embedded with, and both the document and the query prefix. The indexer MUST read the digest from Ollama (`GET /api/tags`, the entry whose name matches the configured model, where a name without a tag means `:latest`) before embedding, and MUST stop with a non-zero code when the model is not listed. It MUST write the metadata on every run that changes the chunks. A run whose configured model, prefixes or dimensions, or whose live digest, differ from the recorded ones MUST re-embed every file, not only the changed ones. mcp-tools reads the same metadata and refuses to search on any difference ([mcp-tools spec](../mcp-tools/spec.md), "Runbook search refuses a model mismatch").

#### Scenario: Metadata after a run
- WHEN the indexer completes with the default configuration
- THEN the metadata table holds `nomic-embed-text`, digest `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f`, dimensions 768, document prefix `search_document: ` and query prefix `search_query: `

#### Scenario: Digest read from Ollama
- GIVEN a fake Ollama HTTP endpoint whose `GET /api/tags` lists `nomic-embed-text:latest` with digest `d1`
- WHEN the indexer runs with model `nomic-embed-text`
- THEN the metadata table records digest `d1`

#### Scenario: Digest changed
- GIVEN the index was built with digest `d1`, and the fake Ollama endpoint now lists `nomic-embed-text:latest` with digest `d2`
- WHEN the indexer runs
- THEN every file is embedded again and the metadata table records `d2`

#### Scenario: Model not in Ollama
- GIVEN the fake Ollama endpoint does not list the configured model
- WHEN the indexer runs
- THEN it exits with a non-zero code naming the model, and the index is unchanged

#### Scenario: Model changed
- GIVEN the index was built with `nomic-embed-text`
- WHEN the indexer runs with another model configured
- THEN every file is embedded again, even with unchanged content hashes
- AND the metadata table holds the new model

#### Scenario: Schema missing
- GIVEN a store without the `runbook_chunks.embedding` column, for example one not created by the init script
- WHEN the indexer runs
- THEN it stops with a non-zero code before embedding, saying the schema is missing

#### Scenario: Dimensions differ from the column
- GIVEN the configured dimensions differ from the `vector` column's
- WHEN the indexer runs
- THEN it stops with a non-zero code before embedding, stating both numbers

### Requirement: Documents are embedded with the document prefix

The indexer MUST send each chunk to the embedding model as the configured document prefix followed by the chunk text (`search_document: <text>` by default). The stored `content` MUST be the chunk text without the prefix.

#### Scenario: Prefix sent, not stored
- GIVEN a fake embedding model that records its inputs
- WHEN a chunk with text "Key signals" is indexed
- THEN the model receives `search_document: Key signals`
- AND the stored `content` is `Key signals`

### Requirement: A run commits atomically

A run that re-embeds anything MUST commit as a whole: every chunk insert and delete of the run, the `indexed_files` rows and the metadata row MUST be written in one database transaction, after all the run's embeddings were computed. The index is small (a few hundred chunks), so one transaction is cheap. A failure at any point, from Ollama or the store, MUST roll the whole run back: the previous index stays fully intact and never mixes chunks from two models or two digests. The indexer then exits with a non-zero code.

#### Scenario: Ollama down
- GIVEN Ollama is unreachable
- WHEN the indexer runs on a changed runbook
- THEN it exits with a non-zero code naming the embedding model
- AND the old chunks of that runbook are still in the index

#### Scenario: Failure in the middle of a full re-index
- GIVEN the index was built with model A, and the indexer runs with model B, so every file is re-embedded
- AND the embedding model fails on the third file
- WHEN the run ends
- THEN it exits with a non-zero code
- AND every chunk row and the metadata row are exactly as they were before the run, all from model A
