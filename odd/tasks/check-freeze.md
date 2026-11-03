# check-freeze

## Objective

Write `infra/scripts/check-freeze.sh`, the check that closes M3 (vision §7): it passes only when the freeze holds.

## Problem

Vision §7's M3 check has four conditions, and no script checks them:

- the tag exists;
- the agent, rules, prompt and taxonomy are unchanged since the tag;
- every holdout-2 recording and its seed postdate the tag;
- no committed result read a holdout before M4.

## Why

A freeze that is checked by hand is a promise. The benchmark's H1–H3 verdicts are only worth something if the code that produced them is the code frozen before the holdouts were recorded and read. The script turns that into a yes or no anyone can rerun.

## Scope

- `infra/scripts/check-freeze.sh`, read-only: it reads git, the recordings' manifest and `evals/results/`, and changes nothing.
- Its tests, in the style of the existing script tests (`infra/scripts/test-lib.sh`).
- A short section in `docs/setup.md`.

Out of scope: creating the tag (a human act on 2026-12-01), recording holdout-2, any run.

## Constraints

- **No CPU-heavy work while A's run is going** (`evals/plans/2026-11-03-a-settled-re2ob-dev.md`): no Maven, no replays, nothing under `agent/` touched. Bash tests only.
- Exit 0 only when every condition holds; one line per failed condition, naming the file or commit.
- The tag name and the frozen paths are named once, at the top of the script, so the human can read them.

## Design

Each condition becomes one function with its own exit status:

1. **Tag:** `git rev-parse --verify` on the tag name, `freeze-m3` by default, overridable by `--tag`.
2. **Unchanged since the tag:** `git diff --quiet <tag> HEAD -- <frozen paths>`. The frozen paths are the agent's main sources and resources (`agent/src/main/`), which hold the loop, the rules, the prompt and the taxonomy. `git diff --name-only` lists the offending files.
3. **Holdout-2 after the tag:** every recording in the manifest whose split is `holdout-2`, and its seed, must carry a time later than the tag's commit time. The writer finds how recordings and seeds record their time; if they do not, the script fails closed on that condition and says why.
4. **No holdout read before M4:** no file under `evals/results/` committed before the M4 date (2026-12-07, overridable) holds a row whose split is `holdout-2`, or an RCAEval holdout: RE2-OB repetitions 2–3 (holdout A) or RE2-SS (holdout B). Lab holdout-1 was read once in M2 by plan, and is allowed. The writer finds how each result names its split and recordings.

## Tasks

- [ ] T1 The script and its tests; the setup section. Route: delegated writer.

## Acceptance criteria

- AC1: on today's repository, with no tag, the script exits non-zero and names the missing tag.
- AC2: in a test repository with a tag and no change, it passes; with a change under a frozen path after the tag, it fails and names the file.
- AC3: a holdout-2 recording older than the tag fails condition 3.
- AC4: a committed result with an RE2-OB repetition 2 row before the M4 date fails condition 4; lab holdout-1 rows do not.
- AC5: the script changes nothing in the repository.

## Checks

- T1: the script's tests, red first.

## Delivery

Forecast: about 250 authored lines. Branch `feat/check-freeze`, from `feat/a-settled-re2ob-dev`. Nothing is pushed.

## Progress

- 2026-11-03: the human approved the feature, to be written while A's run takes the CPU.

## Next step

T1.
