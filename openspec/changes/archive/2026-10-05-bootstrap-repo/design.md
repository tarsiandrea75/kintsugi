# Design: Bootstrap Repository

## Technical Approach

Docs and scaffolding only. Write seven Markdown/text files plus eight empty `.gitkeep` placeholders, following `docs/vision.md` section 8 and the resolved proposal. Each file has one job; cross-file facts live in exactly one place and are linked, not copied. Target total: ~260 authored lines, well under the 400-line budget.

## Architecture Decisions

| Decision | Choice | Rejected alternative | Rationale |
|---|---|---|---|
| Source-of-truth order | `vision.md` > `STATE.md` > latest journal > Engram | Engram as primary memory | Markdown survives any machine or chat; matches planning journal "Continuity" decision |
| CLAUDE.md scope | Project-specific facts and protocol only, ~50 lines | Restating global rules (commits, CLI tools, tone) | Global `~/.claude/CLAUDE.md` already loads; duplication drifts and wastes context |
| Fact placement | Versions/model facts only in `docs/setup.md` (and a future versions ADR) | Repeating them in README/CLAUDE.md | One place to update when facts go stale |
| ADR template | ADR-001 is the template; no separate template file | `docs/adr/template.md` | Avoids an extra file; CLAUDE.md says "copy ADR-001's headings" |
| Journal naming | `YYYY-MM-DD-NN[-slug].md`, NN = per-day counter, slug optional | Single date-only name | Fits existing `2026-10-03-00-planning.md` and planned `2026-10-05-01.md` |
| STATE.md lifecycle | Overwritten each session; history lives in journal | Append-only STATE | Keeps the entry point short; journal is the log |
| Placeholders | Empty `.gitkeep` | README per folder | Folders are not designed yet; no speculative content |

## Data Flow

```
session start: CLAUDE.md -> docs/STATE.md -> latest docs/journal/* -> report (Italian) -> propose next action -> ask
session end ("chiudiamo"): overwrite docs/STATE.md + append docs/journal/YYYY-MM-DD-NN.md (+ optional Engram save)
```

## File Changes

| File | Action | ~Lines | Outline and key content |
|---|---|---|---|
| `CLAUDE.md` | Create | 50 | 1. **Project** (2 lines + links to `docs/vision.md`, `docs/concepts.md`, `docs/setup.md`). 2. **Sources of truth** (order above; contradictions with vision go to the human). 3. **Session protocol**: Start (read STATE + latest journal, report in Italian, propose next action, ask before acting); End on "chiudiamo" (overwrite STATE, add journal entry). 4. **Working conventions**: SDD in small changes within 400-line budget; explain the AI-engineering "why" of each step; check current versions before using a library and record them in an ADR (copy ADR-001 headings); core rule "LLM proposes, deterministic code verifies, policy authorizes". 5. **Language**: conversation Italian, files English. Closing line: "Global user rules apply; this file adds project context only." |
| `README.md` | Create | 25 | Title + one-line tagline; vision pitch (verbatim from vision section 1); "Work in progress" notice with milestone M1 dates; "Why" (local-first thesis, ground-truth evals) in 3 bullets; "Getting started" -> `docs/setup.md`; "Docs" links (vision, concepts, STATE, ADRs). No benchmark table yet. |
| `docs/setup.md` | Create | 60 | Intro (Windows 11, Git Bash/PowerShell; last verified 2026-10-05). Per tool, a "Verify" command and "Install" hint: Java 21 (`java -version`), Maven (`mvn -v`), Docker Desktop (`docker version`), Ollama >= 0.4.6 (`ollama --version`; note why: streaming tool calling), Python 3 (`python --version`). **Model** section: recommended `granite4.1:8b`, alternatives `qwen3:8b`, `qwen3.5:9b`; status "pending smoke test (~10 tool-calling prompts)"; `ollama pull` command; hardware note (32 GB RAM, no GPU). Checklist boxes for each item. |
| `docs/STATE.md` | Create | 20 | Fields: **Updated** (2026-10-05), **Milestone** (M1 — The lab, Oct 5–25), **Last action** (repo bootstrapped via SDD `bootstrap-repo`), **Next action** (exact text: "Monday: Spring AI hello-world calling one tool with the local model"), **Open questions** (versions ADR number — vision reserves ADR-002 for pgvector; model choice pending smoke test), **Blockers** (none). |
| `docs/adr/ADR-001-java-spring-system-python-evals.md` | Create | 35 | Headings (the reusable template): `# ADR-001: <title>`, `Status` (Accepted), `Date`, `Context`, `Decision`, `Alternatives considered`, `Consequences` (positive/negative). Content: Java/Spring for the system, Python only for evals; no version numbers. |
| `docs/journal/2026-10-05-01.md` | Create | 30 | Same shape as the 00 entry: title `# Journal — 2026-10-05 — Session 01: <topic>`, `## What happened`, `## Decisions and why`, `## Next action`. Records bootstrap and the four resolved proposal questions. |
| `.gitignore` | Create | 35 | Grouped with comments: Java/Maven (`target/`, `*.class`, `*.jar` except wrapper), Python (`__pycache__/`, `.venv/`, `*.pyc`, `.pytest_cache/`), IDE (`.idea/`, `*.iml`, `.vscode/`), OS (`.DS_Store`, `Thumbs.db`), secrets (`.env`, `.env.*`, `!.env.example`), machine-local (`.codegraph/`, `.atl/`). |
| `lab/`, `infra/`, `agent/`, `mcp-tools/`, `evals/`, `ui/`, `openspec/specs/`, `openspec/changes/archive/` `.gitkeep` | Create | 0 | Empty files. |

## Interfaces / Contracts

`STATE.md` field names above are the contract read at session start; the journal heading set is the contract for every future entry. Both are defined by example in the files themselves.

## Testing Strategy

| Layer | What | Approach |
|---|---|---|
| Structural | Paths exist, `.gitkeep` x8, links resolve, STATE fields, ADR headings, no versions in ADR-001 | Readback during verify (no test runner; docs-only change exempt from tooling rule) |
| Integrity | Three source docs byte-identical | `git status`/diff shows them unmodified |

## Threat Matrix

N/A — no routing, shell, subprocess, VCS/PR automation, executable-file classification, or process-integration boundary.

## Migration / Rollout

No migration required. Single commit, e.g. `docs: bootstrap repository structure and session protocol`.

## Open Questions

- [ ] Versions ADR number (recorded in STATE.md; decided when written).
- [ ] Model choice confirmed only after the smoke test.
- [ ] `.atl/` ignored: the skill registry becomes machine-local; confirm that is intended (proposal says yes).
