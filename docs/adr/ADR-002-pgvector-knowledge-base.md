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
| Vector store | `spring-ai-starter-vector-store-pgvector` | [PGvector docs](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) |
| Markdown reader | `spring-ai-markdown-document-reader` | [ETL pipeline docs](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html) |
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
- Both roles are `NOSUPERUSER NOCREATEDB NOCREATEROLE`.

### The extension is created at init

- The `vector` extension is **not trusted**: its [control file](https://raw.githubusercontent.com/pgvector/pgvector/master/vector.control) has no `trusted = true` line. A non-superuser therefore cannot run `CREATE EXTENSION vector`.
- The init script creates it as the bootstrap superuser, in the `knowledge` database, before any role uses it. Neither application role ever needs superuser rights.

### Schema and index

- The schema is created by the init script, not by Spring AI: `spring.ai.vectorstore.pgvector.initialize-schema=false`. Why: schema creation needs `CREATE` on the database and, with Spring AI's default DDL, `CREATE EXTENSION`, which only the superuser may run. Keeping DDL in the init script keeps both application roles unprivileged.
- The chunk table follows the layout Spring AI's `PgVectorStore` reads (`id`, `content`, `metadata` as JSON, `embedding vector(768)`).
- **HNSW index with cosine distance** (`vector_cosine_ops`). HNSW needs no training step, unlike IVFFlat, so it works on an almost empty table and on a re-index. Cosine is the distance Nomic's embeddings are meant for.
- An index metadata table records the model name, its digest, the dimensions and both prefixes the index was built with.

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
- It splits by heading with the Markdown reader, then with `TokenTextSplitter` for long sections, so no chunk holds more than 800 tokens. That keeps a single chunk well under the tool's 8 KB cap; if one still exceeds it, `search_runbooks` returns it cut with `[truncated]`, never an empty `OK`. Each chunk carries `service` (from the file name), `section` (its heading), `source` (the repository path) and `content_hash`.
- It stores each file's content hash and skips files that have not changed.
- **A run commits atomically.** All of a run's chunk changes and the metadata row are written in one transaction, after every embedding was computed. The index is a few hundred rows, so this is cheap, and a failure mid-run leaves the previous index fully intact, never a mix of two models.

### Retrieval

`search_runbooks(query, service?, limit?)` is the 8th mcp-tools tool, under the same contract as the other seven ([ADR-004](ADR-004-tools-via-mcp.md)): validated parameters, an 8 KB cap, chunks returned as `untrustedText`, one log line per call. It searches only; it never indexes or writes. The contract is in the [mcp-tools spec](../../openspec/specs/mcp-tools/spec.md); the store and the indexer are in the [knowledge-base spec](../../openspec/specs/knowledge-base/spec.md).

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
