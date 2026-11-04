# verification-levels

## Objective

Every stored report states its verification level, L0 to L3, computed by deterministic code as an annotation, and the scorer reports verified-correct and the L1 rate with Wilson CIs ([ADR-028](../../docs/adr/ADR-028-verification-levels.md)). The agent's part lands before the freeze on 2026-12-01.

## Problem

Only L0 (schema) and L1 (the cited line exists) exist, and no report states a level (`docs/benchmark-limits.md`:26), so vision §6's verified-correct cannot be reported. The assessed report's graph rules are L2 checks run as retries, which vision §3 forbids (ADR-014, open question; vision-revision follow-up 13). The tables view knows no line's owner, and neither view knows whether a line's fact changed between the windows.

## Why

The core rule says deterministic code verifies "at a stated level". Without a level, a correct answer citing an unrelated line counts the same as one citing the cause's changed fact, and the L1 rate (hallucination) is not reported. A check that retries steers the model, so it must annotate instead.

## Scope

ADR-028's Decisions 1-8:

- A per-line fact index from both renderers (owner, section, changed); text byte-identical.
- L2 and L3 checks in `ReportCheck` as annotations; the assessed report's One origin, Graph and Answer become L2 annotations, no longer retried.
- `verification: {level, checks[]}` in the stored report and the incident JSON.
- A `level` column in `summary.tsv`; Verified-correct and L1 rate in the scorer.
- The living specs (`openspec/specs/agent/spec.md`, `openspec/specs/evals/spec.md`), ADR-014's open question closed, `docs/benchmark-limits.md`:26.
- A descriptive (not a gain) level report over existing outputs.

Out of scope: L4 (live probes, follow-up 14), Config C's computation, any prompt or schema change, any change to Config 0's rules.

## Constraints

- **Freeze.** T2-T4 touch `agent/src/main/` and close before 2026-12-01 (`infra/scripts/check-freeze.sh`, `FROZEN_PATHS`, tag `freeze-m3`). T5-T6 are outside the frozen path.
- **No steering.** A level never reaches the prompt, never rejects a report, never retries. Only L0 retries; L1 drops as today.
- **Prompt and goldens unchanged.** The rendered dossier text in both views stays byte-identical; the prompt and report-schema pins stay.
- **Config 0 unchanged.** Its verdicts on lab dev, lab holdout and RE2-OB dev stay identical; `RulesDiagnosis` and `ResourceRule` are not edited (ADR-028 copies their constants and pins them with a test).
- **Old results still score.** `level` is an optional column; a TSV without it scores and regenerates as before.
- **Measurement rules.** Any run starts from a committed plan, reports Wilson 95% CIs, commits its results under `evals/results/`, and runs Config 0 alongside. No holdout is read.

## Design

Summary of ADR-028; the ADR is the reference.

| Level | Check | Applies to | On failure |
|---|---|---|---|
| L0 | schema (today's errors) | every report | retry, then `INVALID_REPORT` (unchanged) |
| L1 | no evidence item dropped | every stored report | item dropped as today; level stays L0 |
| L2 | every cited line's owner is the cause or on its call path (transitive callers and callees); assessed: also One origin, Graph, Answer | reports naming a cause | annotation only |
| L3 | at least one cited line is owned by the cause and `changed` = `YES` | reports naming a cause | annotation only |

- **Level** = the highest `k` with every check at 0..`k` passing.
- **Abstention** (`UNKNOWN|UNKNOWN`): L2 and L3 not applicable (`pass` = null), level at most L1. Verified-correct counts fault rows only; healthy controls stay under the false-alarm rule.
- **Changed**, per section: latency reuses R-DOWN's slow test; resources reuse R-RES; traffic, states and events use "larger count ≥ 3 and ≥ 2× the smaller" (new); status is a restart in the incident window; logs and header lines are `NOT_COMPARED` (new). See ADR-028, Decision 5.
- **New types (T2).** In `agent/.../dossier/`: `LineFact(owner, section, changed)`, an enum `Section`, an enum `Change { YES, NO, NOT_COMPARED }`, and a `WindowChange` holding the per-section predicates. `DossierRenderer` gains a method returning the text and its facts; `ServiceSheets.Rendered` gains `facts` beside `owners`. The existing `render` methods keep their signatures and output.
- **Check output (T3).** `ReportCheck.Result` gains a `Verification(level, checks)` record; `Check(level, name, pass, detail)`. Check names: `schema`, `citations`, `owners-on-path`, `one-origin`, `graph`, `answer`, `cause-fact-changed`.

## Tasks

- [x] T1 ADR-028 and this document. Route: delegated writer (2 non-trivial docs). About 240 lines.
- [x] T2 Line-fact index. RED first: `DossierRendererTest` and `ServiceSheetsTest` assert one `LineFact` per numbered line in both views, the owners on the lab goldens (a client row is the caller's, a server row the callee's, header lines `header`), and `changed` on fixture rows at each section's boundary (latency 2× and 0.5 s, resources R-RES, counts 3 and 2×, a restart, a log line `NOT_COMPARED`); a test pins `WindowChange`'s constants to `RulesDiagnosis` and `ResourceRule`'s; the goldens stay byte-identical. Then `LineFact`, `Section`, `Change`, `WindowChange`, the renderers' second output. Agent spec, "Evidence dossier rendering". Route: delegated writer; trigger: 2+ non-trivial files and reading that prepares the write. Forecast: about 450 lines.
- [ ] T3 L2 and L3 in `ReportCheck`, graph rules converted. RED first: `ReportCheckTest` cases for each level on the plain, hybrid and assessed reports: an item on another branch fails L2; a caller's line passes L2 and fails L3 alone; the cause's changed latency line passes L3; a tool-step citation fails L2; an abstention stops at L1 with L2 and L3 null; a dropped item gives L0; an assessed report with two ORIGINs, an AFFECTED service calling no affected service, or `rootCauseService` not the ORIGIN is **valid** with a failed L2 check and no retry (the existing tests expecting those errors are rewritten, named in the commit). Then the `Verification` record and the checks. Agent spec, "Evidence is checked" and "The assessed report" (requirements plus GIVEN/WHEN/THEN, the open question replaced); ADR-014's open question marked closed by ADR-028. Route: delegated writer; trigger: 2+ non-trivial files. Forecast: about 450 lines.
- [ ] T4 The stored `verification` field. RED first: `AgentLoop` tests in decision and dossier mode show the stored report and the incident JSON hold `verification` after `evidence` and before `stepsUsed`, a level per run, and no retry on an L2 failure (one model call); an `INVALID_REPORT` run stores no level. Then `AgentLoop.stored` (:773-788) and the conclusion path (:695-735). Agent spec, "The report". Route: delegated writer, same writer as T3 if in the same session; trigger: preparation reading of `AgentLoop`. Forecast: about 200 lines.
- [ ] T5 The `level` column and the scorer's rates. RED first: a `ReplayDriverTest` asserting `summary.tsv`'s last column `level`, `-` on a `rules` line and on a run with no report; pytest cases in `evals/tests/` for Verified-correct (fault rows only, healthy rows excluded, abstention never verified) and the L1 rate (rows with a report only), both with Wilson CIs, and a TSV without `level` scoring byte for byte as before. Then `ReplayDriver` (`SUMMARY_HEADER` :104-106, the row writer) and `scoring.py` (`REQUIRED` :37 unchanged, an optional `level`). Agent spec ("Replay of recorded tool outputs", the column list), evals spec, `docs/benchmark-limits.md`:26. Route: delegated writer; trigger: 2+ non-trivial files across agent test and evals. Forecast: about 300 lines.
- [ ] T6 A descriptive level report. First a committed plan under `evals/results/<date>-verification-levels-descriptive/plan.md`: descriptive only, no hypothesis or decision rule, no holdout. Preferred, with no model run: a test-scope driver (`agent/src/test`, not frozen) that reads the local incident files of existing replays (`infra/scripts/out/replay-*`, gitignored), rebuilds each typed dossier from its recording, asserts the re-rendered text equals the stored `DOSSIER` step's output, re-checks the stored `REPORT` step's answer, and writes `summary.tsv` with `level`; then the scorer. The report counts, per fault, the correct answers below L3 whose cause-owned citations are all `NOT_COMPARED` log lines. Runs before 2026-12-01. Inputs: D's lab dev and RE2-OB dev decision runs whose incident files are still on disk, with Config 0 alongside. Fallback, if those files are missing or do not re-render byte for byte: a small cloud decision replay of lab dev (15 cases, about $0.02) under the same plan. RED first for the driver: one recorded incident re-checked to a known level. Route: delegated writer for the driver, inline commands for the run. Forecast: about 250 lines plus results.

Every task closes with a work-unit commit on its slice branch, tests and docs alongside the behaviour, and its commit hash recorded below.

## Acceptance criteria

- AC1: in both views, every numbered line has exactly one line fact, and the rendered text equals the goldens byte for byte.
- AC2: each section's `changed` rule matches ADR-028, Decision 5, at its boundaries; the copied constants equal the rules' constants.
- AC3: no L2 or L3 outcome adds an error, rejects a report or causes a model call; an L0 error still gets exactly one retry, and an L1 drop is unchanged.
- AC4: an assessed report breaking One origin, Graph or Answer is stored, with its L2 check failed and its level at most L1.
- AC5: every stored report has `verification.level` and `verification.checks`, each check `{level, name, pass, detail}`; an abstention's L2 and L3 checks have `pass` null and its level is at most L1.
- AC6: `summary.tsv` ends with `level`; the scorer prints Verified-correct and the L1 rate with Wilson CIs when the column is present, and regenerates every committed result unchanged when it is absent.
- AC7: Config 0's verdicts on lab dev, lab holdout and RE2-OB dev equal the earlier runs in every column but `seconds`, `ranked` and the new `level`.
- AC8: the agent and evals specs state the levels as requirements with GIVEN/WHEN/THEN scenarios; `docs/benchmark-limits.md` no longer says no report states a level; ADR-014's open question points at ADR-028.

## Checks

| Task | Command | Expected |
|---|---|---|
| T2-T5 (agent) | `cd agent && ./mvnw -q test` | RED observed first, then 0 failures |
| T5 (evals) | `cd evals && .venv/bin/python -m pytest -q` | RED observed first, then all pass |
| T2, T3, T4 | the renderer goldens and the prompt and schema pin tests in the agent suite | unchanged, byte-identical |
| T3, T4 (pre-close) | `infra/scripts/replay-agent.sh --route rules --mode decision` with rules v1 and v2 on lab dev and lab holdout, and with `REPLAY_DIR=evals/out/rcaeval/re2-ob-rep1 ... --dataset re2-ob` on RE2-OB dev (`docs/setup.md`) | AC7 |
| T4 (freeze) | `git diff --stat` of `agent/src/main/` against T4's commit before 2026-12-01 | no later change under the frozen path |
| T6 | the plan's checks | levels reported, no gain claimed |

## Delivery

Strategy: `feature-branch-chain` (the human's standing choice for over-budget features). Forecast: about 1,890 authored lines (T1 240, T2 450, T3 450, T4 200, T5 300, T6 250), over the 400-line budget. About 400 lines per task is a planning heuristic, not a cap.

| Slice branch | Tasks | Forecast | Base |
|---|---|---|---|
| `feat/verification-levels` | T1 | about 240 | `feat/lab-realism-min-03-healthy-replay` (`148d94f`) |
| `feat/verification-levels-01-line-facts` | T2 | about 450 | the previous slice |
| `feat/verification-levels-02-level-checks` | T3 | about 450 | the previous slice |
| `feat/verification-levels-03-stored-level` | T4, T5 | about 500 | the previous slice |
| `feat/verification-levels-04-level-report` | T6 | about 250 | the previous slice |

T2-T4 close before 2026-12-01, and T6 runs before the freeze too (the human's choice, 2026-11-04): its report must show how many correct answers rest only on `NOT_COMPARED` log lines while a previous log window can still be added to the agent. Nothing is pushed; push and pull requests are the human's decision.

## Progress

- [x] T0 2026-11-04: the human's decisions recorded in ADR-028; this document written (T1), commit `3c3e335`. Review: passive documentation, structural readback only.
- [x] T2 2026-11-04: line-fact index in both views, this commit. RED: 14 new tests failing against compiling stubs (`DossierRendererTest` 4, `ServiceSheetsTest` 3, `WindowChangeTest` 4, `WindowChangeConstantsTest` 3); GREEN: `cd agent && ./mvnw -q clean test` 744 tests, 0 failures; `git diff --stat HEAD -- agent/src/test/resources` and `-- agent/src/main/java/dev/kintsugi/agent/rules` empty. Route: delegated writer; trigger: 2+ non-trivial files. About 1,045 authored lines (916 added, 129 removed), over the 400-line heuristic and T2's 450 forecast: two renderers' every line call site carries its fact, plus boundary tests in both views. Choices beyond ADR-028, recorded in the agent spec: a latency or resource row with no ratio (a mean missing, the previous mean not above 0, a window not usable) is `NOT_COMPARED`, not `NO`; an event source's total is compared as a row; a `not shown` line (a sum of rows) and a sheet block's `no rows ...` line are `NOT_COMPARED`; a `not shown` line is owned by its hidden rows' one owner, else `header`; the tables' "Other rows" take the sheets' owners (a lab service's row on no edge is that service's); `ServiceSheets.Rendered` keeps a two-argument constructor with no facts, since `ReportCheckTest` builds one.
- [ ] T3
- [ ] T4
- [ ] T5
- [ ] T6

## Next step

T3, L2 and L3 in `ReportCheck`, on `feat/verification-levels-02-level-checks` (branched from T2's commit): its RED tests first.
