# Exploration: bootstrap-repo

> Mirror of Engram topic `sdd/bootstrap-repo/explore` (obs 62). Date: 2026-10-05.

## Scope

Docs and scaffolding only, no code. SDD init and `git init -b main` are already done.

## Current state

- Existing: `docs/vision.md`, `docs/concepts.md`, `docs/journal/2026-10-03-00-planning.md`, `openspec/config.yaml`, `.atl/`, `.git` (no commits).
- `openspec/config.yaml`: `strict_tdd: false` (no test runner yet).

## Gaps vs `docs/vision.md` section 8

In scope:
- `README.md`, `CLAUDE.md`, `docs/STATE.md`
- `docs/adr/ADR-001-java-spring-system-python-evals.md`
- `lab/`, `infra/`, `agent/`, `mcp-tools/`, `evals/`, `ui/`, each with `.gitkeep`
- `.gitignore`
- `.gitkeep` for empty `openspec/specs/` and `openspec/changes/archive/` (git does not track empty directories)

Deferred: `docs/architecture.md`, `docs/runbooks/`, `docs/benchmark.md`, `.github/workflows/`.

## Current versions (verified 2026-10-05, web sources)

- Spring Boot 4.1.1 GA.
- Spring AI 2.0.1 stable (2.0.x supports Boot 4.0.x/4.1.x; 1.1.8 also stable; 2.1.0-M1 preview). Pin 2.0.1 explicitly.
- Java: Boot 4 baseline is Java 17. Java 25 is the newest LTS; Java 21 is still a supported LTS.
- Spring AI `OllamaChatModel` tool calling needs Ollama >= 0.2.8; streaming tool calling >= 0.4.6.

## Model candidates (Ollama, CPU-only, 32 GB RAM)

| Model | Size (Q4) | Context | Notes |
|---|---|---|---|
| **granite4.1:8b** (recommended) | 5.3 GB | 128K | Apache 2.0, April 2026, built for tool calling and RAG, no thinking mode |
| qwen3:8b | 5.2 GB | 40K | Best Spring AI documentation; thinking mode is slow on CPU unless disabled |
| qwen3.5:9b | 6.6–7.6 GB | 256K | Newer, multimodal overhead, less proven for tool calling |

Estimated CPU speed: ~5–10 tokens/s (estimate, not measured). Tool-calling rankings come partly from secondary sources: confirm with a ~10-prompt smoke test on Monday.

## Forks

1. **Maven vs Gradle**: recommend Maven (Spring AI docs and Initializr default, author familiarity).
2. **Environment checklist location**: recommend `docs/setup.md`, linked from README and CLAUDE.md, so the record lives in the repo.
3. **Version pinning**: a separate ADR, not ADR-001 (which covers the Java/Spring vs Python split). Vision lists ADR-002 as pgvector, so numbering needs a decision.

## Other findings

- `CLAUDE.md` coexists with the user's global rules; keep it project-specific.
- Journal naming `2026-10-05-01.md` fits (NN = per-day session counter; `00` was pre-repo planning).

## Verification

Structural readback (docs-only change, no test runner): all paths exist, `STATE.md` has the required fields, links resolve, ADR has status/context/decision/consequences.

## Risks

- Version and model facts change quickly; partly secondary sources.
- ADR numbering vs vision.
