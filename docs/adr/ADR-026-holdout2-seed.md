# ADR-026: One seed, drawn after the freeze tag, fixes holdout-2's case schedule

## Status

Accepted

## Date

2026-11-03

## Context

Vision §7's M3 check requires that "every holdout-2 recording and its seed postdate the tag", and `infra/scripts/check-freeze.sh` condition 3 cannot pass until a seed exists. No file in the repository says what the seed is. Nothing in the lab is random today: the fault injector's parameters are constants in `Fault.java`, `POST /faults` takes only a fault id, and the harness runs F1, F2, F3 in a fixed order (`verify-agent.sh`).

Vision §2's harness loop says what should vary: "Pick a fault from the catalog, with parameters drawn from its declared range, or a healthy control." The point of a holdout recorded after the freeze is that no one can choose its cases knowing the frozen agent. A seed drawn before the tag, or cases chosen by hand, would not guarantee that.

## Decision

1. **One seed for all of holdout-2**, 256 bits read from `/dev/urandom` after the tag `freeze-m3` exists, written as hex to `infra/holdout2/seed.txt` and committed alone. That commit's time is the seed's time, and it must be later than the tag's commit time.
2. **A deterministic generator turns the seed into the schedule**: for each of the cases, in order, its class (a fault or a healthy control) and its parameters, drawn from the ranges declared in the catalog. The schedule is written to `infra/holdout2/schedule.tsv` and committed before the first recording. Anyone can rerun the generator on the seed and compare.
3. **Every holdout-2 manifest row names its schedule case**, and its incident's `startedAt` must be later than the tag.
4. **`check-freeze.sh` condition 3 checks all three:** the seed commit is after the tag, the committed schedule equals the generator's output on the seed, and every holdout-2 recording follows the tag and names a schedule case. Its `seed_failure` is replaced.
5. **The split is named `holdout-2`**, in `record-replay.sh` and `ReplayCases`. The existing `holdout` keeps its name, which `check-freeze.sh` condition 4 relies on.

The classes, their counts and the parameter ranges are decided in their own feature, with this ADR's mechanism.

### Amendment, 2026-11-03: what the check can and cannot prove

The closing review of the first implementation (lineage `review-e6bedfd6bed304aa`) found four gaps; three are closed in code, one is a stated limit.

- **The schedule no longer depends on `random`.** Python promises a stable sequence only for `random()`, not for `shuffle` or `choice`, so a later Python could break condition 3 on a correct schedule. Every draw now comes from SHA-256 of the seed and a counter, which no interpreter version can change.
- **The seed commit must descend from the tag**, as well as carry a later time.
- **Every scheduled case is recorded exactly once, and the schedule's commit precedes the first recording.** A missing case or a second row for one case fails condition 3. A failed recording is not imported; its redo takes the same case.
- **The limit:** git commit times are written by the committer and can be set to anything. The repository stays local, with no remote and no posts (standing preference since 2026-10-07), so nothing outside the author attests the seed's time. Condition 3 therefore proves the ordering of the history as written, not that it was written honestly. What constrains it is the backup bundles kept outside the laptop, whose dates a reader can compare. `benchmark.md` states this limit with H1–H3. A public timestamp of the seed's hash would close it, and remains the human's decision.

## Alternatives considered

- **A seed per case.** Rejected: 75 seeds to date and check, for no gain over one seed and a deterministic schedule.
- **A public randomness beacon.** Rejected for now: it adds a network dependency; a seed committed after the tag already proves it was not known at the freeze.
- **Cases chosen by hand after the tag.** Rejected: nothing would show they were not chosen against the frozen agent.

## Consequences

### Positive

- Condition 3 becomes checkable by anyone, from the repository alone.
- The schedule is fixed before any recording, so no recording can be dropped or redone silently: a missing case shows.

### Negative

- The generator and the catalog's declared ranges must exist before 2026-12-01, so they are frozen with the agent.
- A recording that fails must be redone on the same schedule case, and the redo must be recorded as such.
