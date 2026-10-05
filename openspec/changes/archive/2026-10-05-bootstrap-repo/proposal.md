# Proposal: Bootstrap Repository

## Intent

The repo has only planning docs. Every future session (human + coding agent) needs a stable entry point: project context, a session start/end protocol, a recorded state, and the folder layout from `docs/vision.md` section 8. Docs and scaffolding only.

## Scope

### In Scope
- `CLAUDE.md`: summary, links to `docs/vision.md`, `docs/concepts.md`, `docs/setup.md`; session protocol (start: read `STATE.md` + latest journal, report in Italian, propose next action, ask; end/"chiudiamo": overwrite `STATE.md`, add journal entry); Markdown is the record, Engram is convenience; SDD in small changes; explain AI-engineering "why"; check current versions and record in an ADR; Italian conversation, English files
- `README.md`: vision pitch, work-in-progress notice, link to `docs/setup.md`
- `docs/setup.md`: Windows 11 verify/install checklist (Java 21, Maven, Docker, Ollama, Python 3); model `granite4.1:8b` (alternatives `qwen3:8b`, `qwen3.5:9b`) pending smoke test
- `docs/STATE.md`: next action exactly "Monday: Spring AI hello-world calling one tool with the local model"
- `docs/adr/ADR-001-java-spring-system-python-evals.md` (no versions)
- `docs/journal/2026-10-05-01.md`
- `.gitkeep` in `lab/`, `infra/`, `agent/`, `mcp-tools/`, `evals/`, `ui/`, `openspec/specs/`, `openspec/changes/archive/`
- `.gitignore`

### Out of Scope
- `docs/architecture.md`, `docs/runbooks/`, `docs/benchmark.md`, `.github/workflows/`
- Any code or build files; versions ADR (deferred to Monday)
- Edits to `docs/vision.md`, `docs/concepts.md`, `docs/journal/2026-10-03-00-planning.md`

## Capabilities

### New Capabilities
- `repo-structure`: required top-level files, folders, and placeholders
- `session-protocol`: session start/end rules, `STATE.md` fields, journal naming and content

### Modified Capabilities
None

## Approach

Write Markdown files following exploration recommendations. Keep `CLAUDE.md` project-specific (global rules live elsewhere). Verify by structural readback: paths exist, links resolve, `STATE.md` fields present, ADR has status/context/decision/consequences.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `CLAUDE.md`, `README.md`, `.gitignore` | New | Root entry points |
| `docs/STATE.md`, `docs/setup.md`, `docs/adr/`, `docs/journal/` | New | State, setup, decisions, log |
| Module folders, `openspec/` placeholders | New | `.gitkeep` only |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Model/version facts go stale | Med | Monday smoke test + versions ADR |
| Contradictions below unresolved | Med | Human decides before spec |

## Contradictions to resolve (per `rules.proposal`)

1. `openspec/config.yaml` `rules.tasks`: "first change must include test tooling bootstrap" vs docs-only scope.
2. ADR numbering: vision assigns ADR-002 to pgvector; "next free number" for the versions ADR would take ADR-002.
3. Today (2026-10-05) is Monday; "Monday: ..." in `STATE.md` may read as today, not next Monday. Vision also schedules README/structure for Tuesday and ADR-001 for the weekend.

## Proposal question round

Resolved on 2026-10-05:

1. Test-tooling rule: the rule in `openspec/config.yaml` now applies to the first *code* change; docs-only changes are exempt.
2. Versions ADR number: deferred. Recorded as an open question in `docs/STATE.md`, decided when the ADR is written.
3. "Monday" in `STATE.md` means today's next session (2026-10-05). Keep the exact text "Monday: Spring AI hello-world calling one tool with the local model".
4. `.gitignore` covers Java/Maven, Python, IDE, OS files, `.env`, `.codegraph/` and `.atl/` (machine-local paths).

## Rollback Plan

Delete the added files and folders (no commits exist yet; or `git revert` the bootstrap commit).

## Dependencies

None.

## Success Criteria

- [ ] All in-scope paths exist and are tracked by git
- [ ] `STATE.md` has milestone, last action, exact next action, open questions, blockers
- [ ] All internal links resolve; source-of-truth docs unchanged
