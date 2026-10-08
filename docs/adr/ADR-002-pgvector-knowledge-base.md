# ADR-002: PostgreSQL + pgvector instead of a dedicated vector DB

## Status

Accepted

> Decided on 2026-10-08 from the design in the [feature document](../../odd/tasks/runbook-rag.md). The human chose the embedding model after its tradeoffs were explained. The rest of the design was proposed by Claude after read-only research and **is to be reviewed by the human**. A change after review supersedes this ADR or amends it in place before the feature merges.

## Date

2026-10-08

## Context

The second M2 item is "RAG on runbooks with pgvector" ([vision](../vision.md), M2). It implements the vision's `knowledge` module and the `searchRunbooks` tool. The vision reserves this ADR for "PostgreSQL + pgvector instead of a dedicated vector DB", and its stack table gives the reason: state, memory and the knowledge base in one database, one less component for an SME.

The agent needs to find the runbook section that explains a symptom, from the words of that symptom, even when the wording differs from the runbook's. Keyword search misses paraphrases ("payments hang" against "gateway authorize latency"); semantic search over embeddings does not.

Constraints:

- **The knowledge base must not leak the ground truth.** `docs/runbooks/` is written under an authoring rule that forbids naming faults ([runbooks README](../runbooks/README.md)). `openspec/` and `odd/` do name them, so they must never be indexed.
- **Local first** ([ADR-003](ADR-003-local-models-default.md)): embeddings come from the local Ollama, on a laptop CPU with no GPU.
- **The knowledge base must survive the faults.** The fault-injector breaks the lab's PostgreSQL on purpose ([ADR-009](ADR-009-fault-injector-docker-control.md)); the agent must still be able to read its runbooks while that happens.
- **The agent never writes.** As with the evidence tools ([ADR-004](ADR-004-tools-via-mcp.md)), the agent's role reads only.

## Decision

**A separate PostgreSQL container with the pgvector extension holds a `knowledge` database. A one-shot indexer embeds `docs/runbooks/*.md` with `nomic-embed-text` through Ollama and writes the chunks; mcp-tools serves them read-only through `search_runbooks`.**

### Stack (verified 2026-10-08)

| Component | Version | Notes |
|-----------|---------|-------|
| Store image | `pgvector/pgvector:0.8.7-pg18-trixie@sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0` | pgvector 0.8.7, the latest release, on PostgreSQL 18. The digest is the multi-platform index, read with `docker buildx imagetools inspect` without pulling. The trixie variant matches the Debian base of the lab's `postgres:18.6` |
| Spring AI | 2.0.1 | as in [ADR-006](ADR-006-stack-versions.md) |
| Vector store | none: Spring AI's `PgVectorStore` is not used. The indexer writes and mcp-tools searches with plain SQL through `JdbcTemplate`/`JdbcClient` (see Indexer and Retrieval) | [PGvector docs](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) |
| Markdown parser | `org.commonmark:commonmark` 0.30.0, the latest release (checked on Maven Central, 2026-10-08), declared directly and pinned in `knowledge/pom.xml`, since neither Spring Boot nor Spring AI manages it. `spring-ai-markdown-document-reader` was tried and dropped (see Indexer) | [commonmark-java](https://github.com/commonmark/commonmark-java) |
| Splitter | `TokenTextSplitter` | same ETL docs |
| Embeddings | `spring-ai-starter-model-ollama` with `spring.ai.model.embedding=ollama` | [Ollama embeddings docs](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html) |
| Ollama | 0.31.1 (local) | see the correction note in ADR-006 |
| Embedding model | `nomic-embed-text`, digest `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f` | 137M parameters, F16, 768 dimensions |

### A separate container

- The store is its own compose service, not the lab's `postgres`.
- Why: a fault injected into the lab's PostgreSQL (a stop, a pool exhaustion, a slow query) can never cut the agent off from its knowledge base, and the lab image stays the plain `postgres:18.6` that [ADR-007](ADR-007-lab-persistence-stack.md) pinned.
- The image is pinned by digest, so a re-pushed tag cannot change the extension under the index.

### Database and roles

- One database, `knowledge`, with `REVOKE CONNECT ... FROM PUBLIC`.
- `knowledge_indexer` owns the tables and writes them. Only the indexer holds its credentials.
- `kintsugi_agent` has `CONNECT` on `knowledge` and `SELECT` on its tables, nothing else. It is the same role name mcp-tools already uses, but on a different server with its own password; it has no reach into the lab's databases from here, and no role in this container can reach `faults`, which lives on the other server.
- Both roles are `NOSUPERUSER NOCREATEDB NOCREATEROLE`. Their passwords come from `KNOWLEDGE_INDEXER_DB_PASSWORD` and `KINTSUGI_AGENT_DB_PASSWORD`, both required by the init script. `kintsugi_agent` gets the same session defaults as on the lab's server (read-only, 3 s statement, 5 s transaction and idle-in-transaction timeouts).
- The init script is `infra/knowledge-db/init/01-knowledge.sh`; `mcp-tools`' `KnowledgeStoreRoleTest` runs it on the pinned image. psql reads both passwords from the environment with `\getenv`, so they never appear on a command line.
- **A failed init needs the volume removed.** The postgres entrypoint runs initdb before the init scripts, so a script that stops halfway leaves an initialized volume, and every later start skips the scripts and serves a store without its roles or schema. The init cannot be made atomic. The recovery is the same as for the lab's postgres: fix the cause, then `docker compose down -v` for the store. The script checks its required variables before any SQL, so the commonest cause, a missing password, leaves nothing half-made.
- **T5 note, health check.** `infra/knowledge-db/healthcheck.sh` is the store's compose health check: it passes only when `kintsugi_agent` can read `index_metadata`, the init script's last grant, so an incomplete init never reports healthy (`pg_isready` alone would). It connects over TCP on loopback, which the entrypoint's temporary init server does not listen on, and as `kintsugi_agent` (loopback is trusted by the image's `pg_hba.conf`), so it does not need `POSTGRES_USER` in the `docker exec` environment. T5 wires it in `compose.yaml`.
- **Schema changes need a fresh volume.** The init script runs only on an empty volume, so a store created before a schema change (such as the `indexed_files` table added in T3) keeps its old schema. Before the feature merges, the recovery is `docker compose down -v` for the store and a re-index; after that, a schema change needs a migration.

### The extension is created at init

- The `vector` extension is **not trusted**: its [control file](https://raw.githubusercontent.com/pgvector/pgvector/master/vector.control) has no `trusted = true` line. A non-superuser therefore cannot run `CREATE EXTENSION vector`.
- The init script creates it as the bootstrap superuser, in the `knowledge` database, before any role uses it. Neither application role ever needs superuser rights.

### Schema and index

- The schema is created by the init script, not by Spring AI: no module uses `PgVectorStore`, so nothing could create it, and none should. Why: schema creation needs `CREATE` on the database and, with Spring AI's default DDL, `CREATE EXTENSION`, which only the superuser may run. Keeping DDL in the init script keeps both application roles unprivileged.
- The chunk table, `runbook_chunks`, keeps the layout Spring AI's `PgVectorStore` uses (`id`, `content`, `metadata` as JSON, `embedding vector(768)`), so a later switch to it stays possible; the indexer and mcp-tools name it in their SQL. The 768 is fixed in the schema, not a variable: a model with other dimensions means a migration.
- **HNSW index with cosine distance** (`runbook_chunks_embedding_hnsw`, `vector_cosine_ops`). HNSW needs no training step, unlike IVFFlat, so it works on an almost empty table and on a re-index. Cosine is the distance Nomic's embeddings are meant for.
- An index metadata table, `index_metadata`, holds exactly one row and records the model name, its digest, the dimensions and both prefixes the index was built with.

### Embedding model and the coupling to the data

- **`nomic-embed-text`**, chosen by the human. It is installed locally, small enough for CPU embedding, and has a 2048-token context (`ollama show nomic-embed-text`), so a typical runbook section fits in one input and `TokenTextSplitter` cuts only the long ones.
- Nomic needs task prefixes: documents are embedded as `search_document: <text>` and queries as `search_query: <text>`. Without them the retrieval quality drops.
- **The model is configurable**: its name, dimensions and both prefixes are properties, not constants.
- **The data is not.** Vectors from different models live in different spaces and cannot be compared, and the column has a fixed `vector(768)`. So:
  - the digest is never configured: both the indexer and mcp-tools read it from Ollama's `GET /api/tags`, taking the entry whose name matches the configured model (a name without a tag means `:latest`);
  - the indexer records the model name, digest, dimensions and both prefixes in the index metadata table;
  - `search_runbooks` compares all five with its own configuration and Ollama's current digest before every search, and **refuses** on any difference, with `UPSTREAM_ERROR` and a reason such as `index built with model X, configured Y`. It caches Ollama's digest for at most 60 s, so a model re-pulled under the same name is refused within a minute. A mismatch never returns wrong results silently;
  - changing the model means a property change plus a re-index, and a different dimension also means a schema change.

### Indexer

- The `knowledge/` module is a Spring Boot one-shot job. It runs as a compose service and exits.
- **Allow-list:** it reads only `docs/runbooks/*.md`, from a read-only mount of that directory alone. `openspec/` and `odd/` are not mounted.
- **Leakage gate:** before embedding anything, it scans every file with the runbook authoring rule's pattern (`\b(F[1-8]\b|fault|inject)`, case-insensitive: a fault id, or a word that starts with `fault` or `inject`, so "default" does not match). Any match outside the README's own "Authoring rule" section stops the run with a non-zero exit, and nothing is written.
- It splits by heading, then with `TokenTextSplitter` for long sections, so no chunk holds more than 800 tokens. That keeps a single chunk well under the tool's 8 KB cap; if one still exceeds it, `search_runbooks` returns it cut with `[truncated]`, never an empty `OK`. Each chunk carries `service` (from the file name), `section` (its heading), `source` (the repository path) and `content_hash`.
- It stores each file's content hash in `indexed_files`, owned by `knowledge_indexer` and not readable by `kintsugi_agent`, and skips files that have not changed. The hashes have their own table because a file that yields no chunk (a runbook with only a title) would otherwise have no row to compare with, and be re-read on every run.
- **Implementation (T3).** The `knowledge/` module (Spring Boot 4.1.1, Spring AI 2.0.1) is verified by Testcontainers tests on the pinned image with the real init script and a fake Ollama, and by a manual smoke run against the local Ollama 0.31.1 (768 dimensions, digest `0a109f42…`). Choices made while building it:
  - **Plain SQL through `JdbcTemplate`, not `PgVectorStore`.** `PgVectorStore.add` embeds each document's text itself, so the document prefix would either be stored with the text or not be sent at all, and it writes as it embeds, so a run could not compute every embedding first and then write once. Plain SQL on the init script's schema is a few statements and matches its columns by construction.
  - **Sections come from the CommonMark parser, not from `MarkdownDocumentReader`'s documents.** On the runbooks the reader's visitor loses information: a heading such as ``Slow `POST /reservations` `` gets the title `Slow ` and its inline code moves into the body; paragraphs are joined with no separator; tables are flattened. The indexer parses with the CommonMark parser itself (with source line positions) and keeps each section's raw Markdown, heading line included, so tables, code spans and the heading reach the embedding intact. `section` is the heading's text without Markdown markup. The reader is therefore not a dependency; `TokenTextSplitter` comes from `spring-ai-commons`.
  - Tokens are counted with `TokenTextSplitter`'s own tokenizer (cl100k_base), not Nomic's; 800 of them stays far below Nomic's 2048-token context.
  - One embedding call per file, holding all of its chunks.
  - **A directory with no runbooks stops the run.** Otherwise a missing mount would count every indexed file as removed and empty the index; the image declares no `VOLUME` for the same reason.
- **A run commits atomically.** All of a run's chunk changes, its `indexed_files` rows and the metadata row are written in one transaction, after every embedding was computed. The index is a few hundred rows, so this is cheap, and a failure mid-run leaves the previous index fully intact, never a mix of two models.

### Retrieval

`search_runbooks(query, service?, limit?)` is the 8th mcp-tools tool, under the same contract as the other seven ([ADR-004](ADR-004-tools-via-mcp.md)): validated parameters, an 8 KB cap, chunks returned as `untrustedText`, one log line per call. It searches only; it never indexes or writes.

**Implementation (T4).** Verified by Testcontainers tests on the pinned image with the real init script, known vectors and a fake Ollama, and by an MCP endpoint test. Choices made while building it:

- **Plain SQL through `JdbcClient`, not `PgVectorStore`**, for the same reasons as the indexer: the store would embed the query itself, without the query prefix, and the model guard reads `index_metadata` before any embedding. The query is `ORDER BY embedding <=> CAST(:vector AS vector) LIMIT :limit`, with a fixed `metadata->>'service' = :service` clause when a service is given.
- **Its own read-only pool**, built like the lab pools (Hikari `readOnly`, pgjdbc `readOnlyMode=always`, 2 connections, 2 s timeout, no connection at startup), as `kintsugi_agent` on `kintsugi.mcp.knowledge.host` (default `knowledge-db`), database `knowledge` fixed in code. Its password, `KNOWLEDGE_AGENT_DB_PASSWORD`, has no default but is **not required at startup**: a missing one fails only `search_runbooks`, with a reason naming the variable, so the seven evidence tools never depend on the knowledge store. Failures are classified like the lab's (authentication failed, connection pool busy, unreachable).
- **Each connection turns on pgvector's iterative index scan** (`SET hnsw.iterative_scan = strict_order`, pgvector 0.8): with a service filter, a plain HNSW scan stops after `hnsw.ef_search` (40) candidates and could return fewer than `limit` chunks.
- **The Ollama starter is configured down to what search needs**: `spring.ai.model.chat=none`, `spring.ai.model.embedding=ollama`, `spring.ai.ollama.init.pull-model-strategy=never` (no pull at startup, so mcp-tools starts while Ollama is down), and `spring.http.clients` connect and read timeouts of 2 s and 5 s, the other backends' values. Its WebClient starter is excluded, so no Reactor Netty runs in mcp-tools; both HTTP clients fall back to the JDK's.
- **The model guard compares the configured values first** (name, dimensions, both prefixes) and the digest last, so a different model name is refused without calling Ollama. Only a found digest is cached (60 s), so an unlisted model or an Ollama failure is asked again on the next search.
- **An oversized nearest chunk** is cut by the tool itself to fit the 8 KB cap, ending with `[truncated]`, since the shared runner can only drop whole items. The contract is in the [mcp-tools spec](../../openspec/specs/mcp-tools/spec.md); the store and the indexer are in the [knowledge-base spec](../../openspec/specs/knowledge-base/spec.md).

## Alternatives considered

- **The pgvector image for the lab's own `postgres`.** One container fewer, and closest to the vision's "one DB". But the fault-injector targets that container: a stop or an exhausted pool would take the knowledge base down exactly when the agent needs it, and the lab's image would no longer be the plain `postgres:18.6` its tests run against. Rejected. The vision's point still holds: one engine, one set of skills, no new product to learn.
- **Indexing at mcp-tools startup.** No extra service. But mcp-tools would need write rights on the store and the runbooks mounted, so the agent's server would hold a writer's credentials; startup would wait on Ollama, and an Ollama outage would stop the evidence tools too. Rejected: the indexer is separate so mcp-tools stays read-only.
- **A dedicated vector database, such as Qdrant.** Purpose-built, with filtering and quantization, and Spring AI supports it. But it is another product to run, secure and back up, for four runbooks and a few hundred chunks, where pgvector is more than enough. It would also leave state, memory and knowledge in two engines, against the vision. Rejected.
- **Other embedding models** (installed or available in Ollama):
  - `embeddinggemma`: recent and strong for its size, but not installed, and it needs its own prompt templates;
  - `qwen3-embedding:0.6b`: strong on benchmarks and multilingual, but larger and slower on CPU for no gain on English runbooks;
  - `mxbai-embed-large`: installed and strong, but its context is 512 tokens (a quarter of Nomic's 2048), so long runbook sections would be cut or split more finely.

  Any of them can replace Nomic later through the properties and a re-index; the mismatch guard makes the switch safe.

## Consequences

### Positive

- One database engine for the lab, the store and later the agent's memory; no new product to learn.
- The knowledge base stays up while the lab's PostgreSQL is broken.
- Both application roles are unprivileged, and the agent's role cannot write.
- The model is a configuration choice, and a mismatch between configuration and index fails loudly.
- Re-indexing is cheap: unchanged runbooks are skipped.

### Negative

- **One more container** to run, pin and keep in step with the stack versions.
- **Ollama reachability from containers is open.** Today Ollama listens only on `127.0.0.1:11434` inside WSL, so neither the indexer nor mcp-tools in compose can reach it. The fix (an `OLLAMA_HOST` the Docker bridge can reach, or a loopback-preserving proxy) touches a host service and needs the human; it is decided in T5 of the feature.
- **Any model change needs a re-index**, and a dimension change needs a schema change too.
- **Leakage is controlled, not impossible.** The allow-list and the grep gate stop the known patterns (fault ids, "fault", "inject"). A runbook that hints at a cause in other words would pass; the authoring rule and review remain the main defence.
- **Retrieved text is untrusted.** Runbooks are written by us, but they are still returned as `untrustedText`, so a bad edit cannot become an instruction to the model.
