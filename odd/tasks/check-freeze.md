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

- [x] T1 The script and its tests; the setup section. Route: delegated writer.

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

- 2026-11-03: T1 done by a delegated writer (`da6e874`): `check-freeze.sh` and `test-check-freeze.sh`, a "Freeze check" section in `docs/setup.md`. RED: the script missing, exit 127; GREEN: 25 cases pass; parent spot check the same. 459 authored lines, over the forecast, mostly the tests' throwaway repositories. AC1, AC3, AC4 and AC5 hold; AC2 holds per condition, not as an overall pass (below).
  - **Today's output:** tag, unchanged and holdout-2 FAIL (no tag `freeze-m3`); results PASS. Exit 1.
  - **Condition 3 cannot pass yet: no seed is recorded anywhere.** The manifest (`agent/src/test/resources/replay/recordings.tsv`) has no seed column and no incident has a seed field; the only mention is `odd/tasks/vision-revision.md`:93. A recording's time is its incident's `startedAt`. The splits allowed today are `dev`, `holdout`, `stress`, `probe` (`record-replay.sh`:60): `holdout-2` does not exist yet. Both belong to the holdout-2 recording feature, before 2026-12-01.
  - **Condition 4:** RCAEval holdouts carry the split `holdout`, like lab holdout-1, so the script recomputes each row's blinded id (sha256 of `RE2-SYS/<case>/<rep>`, `convert.py`:146-149) from its fault and truth, and flags RE2-OB repetitions 2–3 and RE2-SS whatever the split label. It reads every version committed before the M4 date, deleted ones included. Today nothing is flagged; lab holdout-1 is read by two committed results (`2026-10-30-holdout1-lab`, `2026-10-31-qwen-holdout1`), each config once, as the plans fixed.
  - The writer's choices: `--repo DIR` for tests; condition 2 also fails on uncommitted files under `agent/src/main/`; condition 3 does not count to 75.

- 2026-11-03: native review of `312d14d..53b4b41`: four lenses, **approved** and acknowledged (lineage `review-a4b54053d317c473`). Its WARNINGs named two fail-open paths in condition 4 (C-quoted paths skipped; tables without `split` skipped), a Python 3.11 dependency, a test that would break once the tag exists, and a stale `docs/STATE.md` line.
- 2026-11-03: those fixed by a delegated writer (`74577f6`), with the four SUGGESTIONs: `git log -z`; the id check on every table with fault, truth and recording; exit 2 below Python 3.11; the missing-tag case in a throwaway repository; an explicit `seed_failure`; `expect_no_line` anchored; the test's blinded id computed by the converter's own code, proved by a mutation. RED observed on every item but the moved test, which needs a tag in the real repository to fail. GREEN 33 cases; parent spot check the same. 114 authored lines. Today's output unchanged.

- 2026-11-03: native review of `53b4b41..1add21b`: four lenses, **approved** and acknowledged (lineage `review-2904c664285e6982`); three lenses were relaunched once, as STATUS reoffered them, after a usage limit stopped them. WARNING R2-stale-next-step fixed in `docs/STATE.md`. SUGGESTIONs not fixed: a comment on the `lstrip("\n")` of the `-z` parse; the `--help` test tied to the text's wrap; one dense sentence in `docs/setup.md`.

## Next step

The feature is complete, but condition 3 stays failing until the holdout-2 recording feature records a seed and its time. That feature is the human's to plan.
