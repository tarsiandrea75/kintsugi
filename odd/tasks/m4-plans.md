# m4-plans

## Objective

Every M4 measurement starts from a plan committed before its run (ADR-015), and the H1–H3 verdicts follow rules fixed before any holdout is read. This covers the RCAEval holdouts A and B, lab holdout-2, and the H1–H3 family with Holm.

## Problem

- `evals/plans/` holds no M4 plan (`docs/STATE.md` item 20).
- The H1–H3 rules (`docs/vision.md`:24-30) are written as CI bounds, but Holm needs p-values. No alpha, sidedness, CI method, or rule for combining several conditions is written down.
- `kintsugi_evals benchmark` only copies per-directory score outputs (`benchmark.py`:1-20, 128-140). It has no H1–H3 verdict, no H1–H3 Holm family, no pooling across tracks and no Config C section.
- `escalate` selects a rule over the grid; no fixed-rule mode is verified for the holdout read (ADR-030 fixes `k = L2, t = off`).
- Three open decisions:
  - **Configs on holdout-2:** not stated anywhere.
  - **D's budget:** 300 cases × 3 cloud repeats exceeds the ~$3.6 left (`docs/vision.md`:278).
  - **A's 200 cases:** reached only with holdout B, since 60 + 90 + 75 = 225.

## Why

H1–H3 are the project's headline. A verdict rule written after the numbers is not pre-registered, and a measurement without a committed plan breaks the project's protocol.

## Scope

- An ADR fixing the H1–H3 verdict procedure (human decision, 2026-11-06).
- The H1–H3 verdict computation in the scorer, plus a fixed-rule mode for `escalate`.
- Plans for holdout A (RE2-OB repetitions 2–3), holdout B (RE2-SS), lab holdout-2, and the H1–H3 family.
- A converter check on the holdout and RE2-SS paths without reading a holdout: headers and counts only.

Out of scope: any holdout read or run (M4, from 2026-12-07); the freeze tag and the holdout-2 seed (human, 2026-12-01).

## Constraints

- **No holdout is read.** `infra/scripts/check-freeze.sh` condition 4 holds until 2026-12-07.
- **Freeze.** Nothing under `agent/src/main/` changes.
- **Pre-registration.** Plans and the ADR are committed before any holdout run; later changes are labelled exploratory.

## Design

ADR-031 is the reference for the verdict procedure. In short:

- each hypothesis has a one-sided p-value against its stated margin, computed by the case-level cluster bootstrap on paired cases;
- alpha is 0.025 one-sided, which matches the vision's "95% CI";
- a hypothesis with several conditions holds only if every condition holds, and its p-value is the maximum (intersection-union);
- Holm runs over {H1, H2, H3};
- 95% CIs are reported alongside.

## Tasks

- [x] T1 ADR-031 H1–H3 verdict procedure, vision §1 pointer. Route: inline (docs only).
- [ ] T2 Open decisions resolved with the human: holdout-2 configs, D budget and repeats, A's 200 cases. Route: inline.
- [ ] T3 Verdict code: H1–H3 p-values, intersection-union, Holm over the family, pooling over tracks, and `escalate --rule` fixed mode. Tests first. Route: delegated writer.
- [ ] T4 Converter dry check of the holdout A/B and RE2-SS paths (headers and counts, no scoring). Route: delegated.
- [ ] T5 Plans: holdout A, holdout B, holdout-2, H1–H3 family. Route: inline (docs only).
- [ ] T6 `docs/STATE.md` updated. Route: inline.

## Acceptance criteria

- ADR-031 and the four plans are committed before 2026-12-07, and none reads a holdout.
- On synthetic paired data, the verdict code reproduces hand-computed one-sided p-values, intersection-union maxima and Holm decisions.
- `escalate --rule k=L2,t=off` computes C under the fixed rule, with no grid selection.

## Checks

- `evals/.venv/bin/python -m pytest evals -q`
- `infra/scripts/check-freeze.sh`: condition 4 stays green, and the other conditions change only through the missing tag.

## Delivery

- The forecast is about 700 authored lines, so the strategy is `feature-branch-chain` (standing choice).
- Slice 1 holds T1–T2, slice 2 holds T3–T4 and slice 3 holds T5–T6.

## Progress

- 2026-11-06: explored. The human chose the verdict procedure in Design.
- 2026-11-06: T1 done. ADR-031 written; vision §1 points to it (structural readback, docs only).

## Next step

T2: the holdout-2 configs.
