# Feature: runbook-rag

Locator: `odd/tasks/runbook-rag.md` · Engram mirror: `odd/runbook-rag/tasks` · Branch: `feat/runbook-rag` (from `feat/mcp-tools-03-wiring` at `1131f9d`)

## Objective

Give the agent semantic search over `docs/runbooks/`. This is the second M2 item, "RAG on runbooks with pgvector" (`docs/vision.md:155`). It implements the vision's `knowledge` module (`docs/vision.md:70`) and the `searchRunbooks` tool (`docs/vision.md:73`). ADR-002 is reserved for this in the vision: "PostgreSQL + pgvector instead of a dedicated vector DB" (`docs/vision.md:201`).

## Problem and why

The agent can read evidence through mcp-tools, but it cannot read the runbooks that tell an operator what a symptom means and where to look. Semantic search finds the relevant runbook section from the words of a symptom, even when the wording differs.

The knowledge base must stay clean. Only `docs/runbooks/*.md` is ever indexed, never `openspec/` or `odd/`, because those name the faults.

## Scope

- In:
  - ADR-002 and the living spec;
  - a separate pgvector container holding a `knowledge` database;
  - a one-shot indexer, the `knowledge/` module;
  - `search_runbooks` as the 8th mcp-tools tool;
  - compose wiring;
  - a live check script;
  - docs.
- Out:
  - long-term memory of past incidents (the `memory` module);
  - the agent loop that calls the tool;
  - re-ranking and hybrid keyword search.

## Design

The embedding model was decided by the human on 2026-10-08. The rest was proposed by Claude after read-only research; the human chose the model after it was explained, and the other choices are open to review.

- **Embedding model: `nomic-embed-text`.** It is served by the local Ollama 0.31.1 at digest `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f`. It has 137M parameters, F16 weights and 768 dimensions.
  - The model is **configurable, not hardcoded**: the name, dimensions and the query and document prefixes are properties. Nomic needs `search_query: ` and `search_document: ` prefixes.
  - **The data is coupled to the model.** Vectors from different models cannot be compared, and the column has a fixed `vector(768)`. The index therefore records the model name, digest and dimensions.
  - `search_runbooks` refuses to search when the configured model differs from the indexed one. A mismatch never produces wrong results silently.
  - Changing the model means a property change plus a re-index.
- **Store.** A separate container, `pgvector/pgvector:0.8.7-pg18-trixie` pinned by digest. The trixie variant matches the lab's `postgres:18.6` base.
  - Why separate: a fault injected into the lab's postgres can never cut the agent off from its knowledge base, and the lab image stays unchanged.
  - The `vector` extension is **not** trusted, so it is created by the init script as the bootstrap superuser.
  - The store uses a `knowledge` database with `REVOKE CONNECT FROM PUBLIC`, an HNSW index and cosine distance.
- **Roles:**
  - `knowledge_indexer` owns the tables and writes them;
  - `kintsugi_agent` has CONNECT and SELECT only, and stays read-only;
  - neither role is a superuser.
- **Indexer.** The `knowledge/` module is a Spring Boot one-shot job that runs as a compose service and exits.
  - It reads an allow-list of `docs/runbooks/*.md` from a read-only mount. Before indexing, it rejects any file that matches the leakage pattern, except the README rule.
  - It splits by heading, using the Markdown reader plus a token splitter. Each chunk is tagged with `service` (from the file name) and its section heading.
  - Each runbook's content hash is stored, and unchanged files are skipped.
- **Tool `search_runbooks(query, service?, limit?)`:**
  - `query`: 3–300 characters;
  - `service`: the same enum as the other tools;
  - `limit`: default 5, maximum 10;
  - the output is the chunks as `untrustedText`, each with `service`, `section` and `score`;
  - same `ToolCallRunner` contract, 8 KB cap and call log;
  - when Ollama or the store is down, the outcome is `UPSTREAM_ERROR` and the other tools keep working.
- **Reaching Ollama from containers.** Today Ollama listens only on `127.0.0.1:11434` inside WSL, so containers cannot reach it. Possible fixes:
  - `OLLAMA_HOST` on an address the Docker bridge can reach;
  - a loopback-preserving proxy.

  The choice is made in T5. It needs the human because the change involves a host service and sudo.

## Acceptance criteria

- Only `docs/runbooks/*.md` is indexed. A runbook that fails the leakage check stops the indexer.
- `search_runbooks` returns the right runbook section for symptom-worded queries.
  - The live check script asserts at least: the order-service payment-timeout section, the inventory-service slow-reservation section, and the gateway runbook in the top 3 for a rate-limit query.
  - Amended 2026-10-08, T5: the gateway runbook has no rate-limit section; 429 appears only in its tables. On "gateway answering too many requests" it ranks 2nd, after payment-service "503 on POST /payments". This is a retrieval-quality follow-up, not hidden.
- A model mismatch between the configuration and the index is refused with a clear error.
- Neither the store nor the tool can reach `faults`. `kintsugi_agent` cannot write the knowledge base.
- `./mvnw test` passes in `knowledge` and `mcp-tools`. The verify scripts still pass.

## Delivery

- `auto-chain` with `feature-branch-chain`. Forecast: about 1,500 lines. Slices are recorded below as they happen.
- RDD: on; consent is a standing grant.

## Tasks

- [x] **T1 — ADR-002, living spec `openspec/specs/knowledge-base/spec.md`,** the mcp-tools spec entry for `search_runbooks`, and a stale-version fix in ADR-006 (local Ollama is 0.31.1). Route: delegated writer.
- [x] **T2 — The knowledge store:** the pgvector container's init script (database, extension, roles, schema), tested with Testcontainers on the same image. Route: delegated writer.
- [x] **T3 — The indexer (`knowledge/`):** allow-list, leakage check, splitting with metadata, model metadata and content hashes, tested with a fake embedding model. Route: delegated writer.
- [x] **T4 — `search_runbooks` in mcp-tools,** with the model-mismatch guard, tested with Testcontainers and a fake embedding model. Route: delegated writer.
- [x] **T5 — Compose wiring, Ollama reachability (needs the human), `verify-rag.sh` run for real, docs.** Route: delegated writer.

## Progress

- 2026-10-08: read-only research done.
  - Facts: Ollama 0.31.1 runs in WSL on loopback only; three embedding models are installed; pgvector 0.8.7 is the latest release; `vector` is not a trusted extension; Spring AI 2.0.1 has the pgvector store and the Markdown reader.
  - The human chose `nomic-embed-text` after learning that the model is configurable but the data is not.
  - Branch created.

- 2026-10-08: T1 done by a delegated writer (docs only).
  - Created ADR-002 and `openspec/specs/knowledge-base/spec.md`.
  - Added `search_runbooks` to the mcp-tools spec; there are now 8 tools.
  - Added a dated Ollama 0.31.1 correction to ADR-006.
  - pgvector image index digest: `sha256:9d9c930220cb9bf2f956d10a8f909cf9973d9672ca0278aef2c1e6facccad2e0`.
  - Leakage grep: clean.
  - Decided by the writer, to review:
    - `README.md` is indexed, without a `service`, except its "Authoring rule" section, which is exempt from the leakage gate and never indexed;
    - `kintsugi_agent` is reused as the reader role on the knowledge store, with its own password.
  - Corrections to the research:
    - `nomic-embed-text` has a 2048-token model context;
    - Ollama embeddings come from `spring-ai-starter-model-ollama` with `spring.ai.model.embedding=ollama`.
  - Open for T4: the spec's mismatch guard compares only the model name and dimensions. mcp-tools could also compare the digest by reading Ollama's `/api/tags`; decide in T4 and update the spec.

- 2026-10-08: T1 range reviewed (medium risk, reliability lens), approved and acknowledged (`review-978a093001fe6c18`). It raised 4 spec warnings, fixed in the next commit:
  - the leakage pattern matched "default": it is now `\b(F[1-8]\b|fault|inject)`, case-insensitive;
  - the mismatch guard ignored the digest: it now compares model name, digest (from Ollama `/api/tags`, cached at most 60 s), dimensions and both prefixes;
  - a re-index failing mid-run could mix models: a run now commits atomically;
  - an oversized nearest chunk would return OK with zero items: it is now cut with `[truncated]`, and chunks are at most 800 tokens.
- 2026-10-08: T2 done by a delegated writer.
  - `infra/knowledge-db/init/01-knowledge.sh` creates:
    - the `knowledge` database, with CONNECT revoked from PUBLIC;
    - the `vector` extension, created as superuser;
    - `knowledge_indexer`, the owner;
    - `kintsugi_agent`, SELECT only, with read-only and timeout defaults;
    - `runbook_chunks` with `vector(768)` and the HNSW cosine index;
    - a single-row `index_metadata`.
  - `KnowledgeStoreRoleTest`, run on the pinned pgvector image: 18 tests, RED observed (the script did not exist), then GREEN.
  - mcp-tools has 158 tests, mvn exit 0, run by the parent.
  - Compose wiring of the store is in T5.

- 2026-10-08: T2 range reviewed (high risk, 4 lenses), approved and acknowledged (`review-ea539b09f103c230`). These warnings are fixed in T3 part A:
  - a failed init leaves a half-initialized volume: documented as needing `down -v`, plus a health check that passes only after a complete init;
  - "No chunk MUST" wording;
  - vacuous assertions in `KnowledgeStoreRoleTest`.

  The passwords are now read with psql `\getenv`, so they stay off the command line.
- 2026-10-08: T3 done by a delegated writer.
  - Part A passes alone: mcp-tools 160 tests, exit 0, run by the parent.
  - Part B is the `knowledge/` module, a one-shot indexer. `knowledge` has 29 tests, exit 0: two runs by the writer, one by the parent.
  - Decided by the writer, to review:
    - **`JdbcTemplate`, not `PgVectorStore`:** the store would embed the text it stores, so the document prefix could not be sent without also being stored, and it writes as it embeds, which breaks the one-transaction run.
    - **Sections are split with the CommonMark parser:** `MarkdownDocumentReader` mangles headings with inline code and flattens tables. `TokenTextSplitter` then caps each section at 800 tokens, counted with cl100k as an approximation of nomic's tokenizer.
    - **An empty runbooks directory stops the run,** so a missing mount cannot empty the index. This guard was added beyond the spec, and the spec was updated.
  - Real runbooks, chunks per file: README 2, order-service 13, payment-service 12, inventory-service 12, gateway-mock 12. None is over 800 tokens, and none comes from the authoring rule.
  - Smoke run against the real Ollama: digest `0a109f42…` read from `/api/tags`, and 768 dimensions.
  - **Size exception:** Part B is about 1,800 lines (620 main, 900 tests). It is a whole new module with one test per spec scenario, and splitting it would leave an indexer that cannot run.
  - Decided for T4: search uses `JdbcTemplate` as well, for the query prefix and the digest guard. The mcp-tools spec's mention of `PgVectorStore` is corrected in T4.

- 2026-10-08: T3 range reviewed (high risk, 4 lenses), approved and acknowledged (`review-04b87f81b77827a2`). Its warnings are fixed in T4 part A:
  - the health check read an unset `POSTGRES_USER`: it now connects as `kintsugi_agent` over loopback;
  - a chunkless runbook was re-processed on every run: a new `indexed_files` table holds per-file hashes, readable by the indexer only;
  - an unexplained `subList` in a test;
  - a misleading pom comment: the unused Markdown reader is dropped, and CommonMark 0.30.0 is declared directly, giving the same chunk counts;
  - the -1 dimension sentinel: a missing schema now throws.

  Part A passes alone: `knowledge` 31 and mcp-tools 164 tests, exit 0, run by the parent.
- 2026-10-08: T4 done by a delegated writer: `search_runbooks` in mcp-tools.
  - mcp-tools has 193 tests, exit 0: two runs by the writer, one by the parent.
  - **No real RED:** the code was written before the tests. As a substitute, the writer removed the digest check and the chunk cut on purpose; 4 tests failed, then passed when restored. This is a process deviation from tests-first, recorded honestly.
  - Design, decided by the writer, to review:
    - search with `JdbcClient` and `<=>`;
    - `hnsw.iterative_scan=strict_order` on each connection, so a service filter still fills `limit`;
    - the guard checks configuration fields first, then the digest from `/api/tags`, cached 60 s;
    - the Ollama starter with chat off, no model pull, and JDK HTTP clients with 2 s connect and 5 s read timeouts (the WebClient starter is excluded).
  - **Accepted (parent):** `KNOWLEDGE_AGENT_DB_PASSWORD` is not required at startup. Without it, only `search_runbooks` fails, with a reason naming the variable, and the other 7 tools keep working. This matches the rule that an unavailable source is evidence, not a reason to stop.
  - **Size exception:** about 1,470 lines (520 main, 770 tests), one cohesive tool with its own datasource, guard and fake Ollama.
  - Open: the Ollama timeouts are untested. T5 must set `spring.ai.ollama.base-url` and `KNOWLEDGE_AGENT_DB_PASSWORD`.

- 2026-10-08: T4 range reviewed (high risk, 4 lenses), approved and acknowledged (`review-cd4ca8ca85ecb951`).
- 2026-10-08: T5 done by a delegated writer. It stopped once on the session limit and was resumed from the same state.
  - **Part A** fixes the T4 review findings:
    - a Spring-context test proves the 5 s Ollama read timeout, with no retry;
    - nested tests prove `/actuator/health` stays UP with the lab DB down, the knowledge store down, or its password unset;
    - the misleading test is renamed, and a real "embed unreachable" case is added;
    - a null `models` list gives `UPSTREAM_ERROR`;
    - `section` stays outside `untrustedText`, but control characters are stripped, it is cut at 100 characters, and the tool description and spec call it untrusted.

    The tests were mutation-checked: re-enabling db health and raising the timeout make them fail.
  - **Part B:**
    - an `ollama-proxy` service (`alpine/socat:1.8.1.3` pinned by digest), `network_mode: host`, bound only to `172.17.0.1:11434`, chosen by the human; Ollama stays on loopback;
    - `knowledge-db`, not published on the host;
    - the one-shot `knowledge-indexer`, with only `docs/runbooks` mounted read-only;
    - mcp-tools depends on the indexer with `required: false`, so the 7 evidence tools never depend on Ollama. `up --wait` then exits 1 when only the indexer failed, and `start_lab` accepts that case with a WARN;
    - `verify-rag.sh`;
    - the indexer logs a fully skipped run.
  - **Parent:** updated `verify-mcp.sh` and `docs/setup.md` to 8 tools (the writer's surface did not include them).
  - **Results:**
    - mcp-tools: 200 tests (XML reports), exit 0;
    - knowledge: 31 tests, exit 0;
    - `verify-rag.sh`: exit 0, twice by the writer and once in the parent's spot check (21 PASS lines);
    - `verify-mcp.sh`: exit 0 after the parent's fix;
    - `verify-f1.sh` … `verify-f4.sh`: exit 0 (writer).
  - **Model mismatch, live:** a one-off mcp-tools configured with `mxbai-embed-large` was refused.
  - **Indexing:** 51 chunks, about 38 s of embedding and about 50 s overall. A second run skips every file.
  - **Live top hits:**
    - "orders stuck waiting for payment after a timeout": order-service "Orders stuck in PAYMENT_PENDING" (0.78);
    - "reservations are very slow": inventory-service "Slow POST /reservations" (0.754);
    - "gateway answering too many requests": gateway-mock 2nd (0.687), after payment-service 503 (0.704).
  - **Risk accepted and recorded in ADR-002:** any container on this host can reach Ollama through the proxy.
  - **The human must add** to `infra/.env.example`:
    - `KNOWLEDGE_BOOTSTRAP_PASSWORD=postgres`
    - `KNOWLEDGE_INDEXER_DB_PASSWORD=knowledge_indexer`
    - `KNOWLEDGE_AGENT_DB_PASSWORD=knowledge_agent`
    - `OLLAMA_PROXY_BIND_IP=172.17.0.1`

- 2026-10-08: T5 range reviewed (high risk, 4 lenses), approved and acknowledged (`review-9b423c3e231637f5`).
  - The proxy-exposure warning is the risk the human accepted with the socat choice; it is recorded in ADR-002.
  - The other findings were fixed by a delegated writer in the next commit:
    - a failed proxy bind no longer blocks the lab. The proxy has `restart: unless-stopped` and only the indexer depends on it. Proven live: with `172.17.0.1:11434` taken, the lab came up, `start_lab` printed WARN lines and `verify-mcp.sh` passed;
    - `start_lab` accepts a failed `up --wait` only when the indexer exited non-zero or the proxy is down, and every other service is running, and healthy where it has a healthcheck. It prints compose's errors;
    - `verify-rag.sh` reads the expected digest live from Ollama;
    - `section()` also blanks U+2028, U+2029 and format characters;
    - the connect timeout has its own reason;
    - the second-run check names every runbook.
  - mcp-tools keeps waiting for the indexer: with Compose 5.1.3, `up --wait` fails on a one-shot service no one waits on. This is recorded in ADR-002.
  - Results: mcp-tools 212 tests (XML), exit 0, run by the parent. `verify-rag.sh` exit 0, run by the parent. `verify-mcp.sh` and `verify-f1.sh` exit 0 (writer).

- 2026-10-08: the fix range `09c6d5f..80c167d` reviewed (high risk, 4 lenses), approved and acknowledged (`review-3d587daebdfa052c`). Follow-ups, not fixed (the one-correction-per-range rule):
  - WARNING (R4): `knowledge_only_failure` may read an indexer exit state left by an earlier `up`, and so mask another failure;
  - WARNING (R3): that bash classifier has no test;
  - WARNING (R2): the separator test uses invisible raw Unicode literals instead of escapes;
  - suggestions: the accepted-path PASS message always names both services; the global `UP_ERRORS` is hidden state.

- 2026-10-22: the human confirmed every decision marked "to review": `README.md` indexed without its "Authoring rule" section; `kintsugi_agent` as the knowledge store's reader role; `JdbcTemplate` instead of `PgVectorStore`; CommonMark sections cut at 800 tokens counted with cl100k; an empty runbooks directory stops the run; search with `<=>`, `hnsw.iterative_scan=strict_order`, the model guard (configuration, then the `/api/tags` digest cached 60 s) and 2 s / 5 s Ollama timeouts. Why: in use since 2026-10-08, reviewed, and no run or replay has shown a problem tied to them.

## Next step

Done. The decisions marked "to review" were confirmed by the human on 2026-10-22 (see Progress).
