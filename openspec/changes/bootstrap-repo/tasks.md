# Tasks: Bootstrap Repository

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~260 (range 230-300), additions only |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR / single commit |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending (not needed) |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Full bootstrap (all files) | PR 1 | `eza -R` readback + `rg` checks per task | N/A: docs only, no runtime | Delete added files; source docs untouched |

Strict TDD N/A (docs-only, no runner). Verification = structural readback. Threat matrix N/A.

## Phase 1: Foundation (placeholders and ignore rules)

- [x] 1.1 Create empty `.gitkeep` in `lab/`, `infra/`, `agent/`, `mcp-tools/`, `evals/`, `ui/`, `openspec/specs/`, `openspec/changes/archive/`. Check: 8 files exist, size 0. (repo-structure: Folder placeholders)
- [x] 1.2 Create `.gitignore` (Java/Maven, Python, IDE, OS, `.env*` with `!.env.example`, `.codegraph/`, `.atl/`). Check: `rg` finds each category. (Gitignore coverage)

## Phase 2: Core documents

- [x] 2.1 Create `docs/adr/ADR-001-java-spring-system-python-evals.md`: Status, Date, Context, Decision, Alternatives, Consequences; no versions. Check: headings present, no digits-as-versions. (ADR-001)
- [x] 2.2 Create `docs/setup.md`: Windows 11 verify/install for Java 21, Maven, Docker, Ollama >= 0.4.6, Python 3; model `granite4.1:8b`, alternatives `qwen3:8b`, `qwen3.5:9b`, pending smoke test; checklist boxes. Check: all five tools and three models appear. (Setup checklist)
- [x] 2.3 Create `docs/STATE.md` with Updated, Milestone, Last action, Next action (exact "Monday: Spring AI hello-world calling one tool with the local model"), Open questions (versions ADR number undecided; model choice), Blockers. Check: five fields, exact string via `rg -F`. (STATE.md fields)

## Phase 3: Entry points

- [x] 3.1 Create `CLAUDE.md` (~50 lines): project links to vision/concepts/setup; source-of-truth order (Markdown over Engram); session start (4 steps) and end ("chiudiamo": overwrite STATE, add journal); SDD small changes, explain AI-engineering "why", versions in ADR (copy ADR-001 headings); Italian chat, English files; no global-rule duplication. Check: each convention present via `rg`. (CLAUDE.md content, Session start/end, Record hierarchy)
- [x] 3.2 Create `README.md`: tagline, vision pitch verbatim from vision section 1, WIP notice with M1 dates, three "Why" bullets, link to `docs/setup.md`, docs links. Check: pitch, notice, links present. (README)

## Phase 4: Journal and state close-out

- [x] 4.1 Create `docs/journal/2026-10-05-01.md` (`# Journal — 2026-10-05 — Session 01: bootstrap`; What happened; Decisions and why, including four resolved proposal questions; Next action). Link `openspec/changes/bootstrap-repo/{proposal,design,tasks}.md` and specs. Add placeholder `Bootstrap commit: <hash pending>` (filled after commit, only on human request). Check: file exists, non-empty, links resolve. (Journal entry)
- [x] 4.2 Re-read `docs/STATE.md`; confirm Last action mentions SDD `bootstrap-repo` and Next action is still exact.

## Phase 5: Verification

- [x] 5.1 Resolve every relative link in `CLAUDE.md`, `README.md`, journal; confirm each target exists.
- [x] 5.2 Confirm no out-of-scope paths: `docs/architecture.md`, `docs/runbooks/`, `docs/benchmark.md`, `.github/workflows/`, code, build files, versions ADR.
- [x] 5.3 Confirm `docs/vision.md`, `docs/concepts.md`, `docs/journal/2026-10-03-00-planning.md` unmodified (timestamps/diff; git diff after init if available).
- [x] 5.4 Walk every spec scenario (repo-structure, session-protocol) and tick it; report to human. Do NOT commit unless asked; suggested message `docs: bootstrap repository structure and session protocol`.
