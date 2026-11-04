# ADR-015: The evaluation protocol and a standard-library scorer

## Status

Accepted

## Date

2026-10-28

## Context

`docs/vision.md` §1 pre-registers H1–H3, and §6 lists the protocol in one line per rule. Until now no rule was executable:
- `evals/` was empty and every replay result was git-ignored;
- rates were quoted without intervals, on 5 cases per fault;
- keep-or-revert decisions compared counts by eye;
- nothing recorded whether a plan existed before a run.

With 5 cases per fault, 4/5 has a Wilson 95% CI of [38%, 96%]. Small samples and repeated looks make noise look like progress. ADR-001 puts the evals in Python under `evals/`.

## Decision

### The measurement plan

Before every run that a decision or a claim rests on, a plan is committed under `evals/plans/`, copied from `evals/plans/TEMPLATE.md`. It names:
- the hypothesis;
- the cases and their split (dev, holdout-1, holdout-2, an RCAEval split);
- the configs (0, A, B, C, D, D+ of vision §5) and their exact settings;
- the metric;
- the decision rule, written before the numbers are seen;
- the date and the commit the run uses.

The plan's commit must precede the result's commit. A plan written after its run is marked "retrospective" and its result is exploratory.

### Intervals and tests

- Every rate gets a Wilson 95% interval. Why: the normal interval leaves [0, 1] and undercovers on small n.
- Two configs on the same cases are compared paired, with the exact two-sided McNemar test on the discordant cases. Why: pairing removes case difficulty from the comparison, and the exact binomial is valid on small counts, where the chi-squared approximation is not.
- When a case has several repetitions (the 3 cloud repeats, RCAEval's correlated runs), the case stays the unit. The paired difference gets a cluster bootstrap interval: whole cases are resampled, each case weighted once. The bootstrap takes an explicit seed, recorded with the result, so a rerun gives the same interval. McNemar is used only with one outcome per case and config.
- H1–H3 get a Holm correction across the three tests. Everything else is exploratory and says so.

### Holdouts

A holdout is read once, by a run whose plan was committed first. The result directory records the holdout name and the plan's path. A second read of the same holdout is labelled exploratory in its plan and in `docs/benchmark.md`; it never replaces the first.

### The dev keep rule

On dev, a variant is kept only with a net gain of at least 3 cases over the baseline, on the same cases, and no fault class losing more than 1 case net. Why: on 15 cases, a gain of 1 or 2 is within the noise of a rerun, and a pooled gain can hide a class that got worse.

### Scoring a case

- On a fault case, the answer is correct when the run completed and its `service|category` equals the truth. `UNKNOWN|UNKNOWN` is an abstention: it counts as a miss for AC@1 and lowers coverage. Any other answer, or no report, is a miss.
- On a healthy case, `UNKNOWN|UNKNOWN` is correct and any cause is a false alarm.
- The healthy truth token is `NONE|NONE`. It was provisional until lab-realism fixed the format of healthy recordings; the amendment of 2026-11-03 below makes it final.
- Coverage is the share of fault cases with an answer; selective accuracy is the accuracy on those cases.

### Results

The output of every scored run is committed under `evals/results/<date>-<name>/`, with a copy of each input `summary.tsv`, so one command regenerates every table from committed files.

### Tooling

- The scorer is the `kintsugi_evals` package under `evals/`, run as `python -m kintsugi_evals score`.
- It uses the Python standard library only. Why: a measurement must run with a bare `python3`, with no install step that could drift between runs, and the statistics here (a binomial tail, a Wilson formula, a seeded resample) need no numeric library. This narrows ADR-001's "pandas, matplotlib" to what the scorer needs today; plotting can add a dependency later, outside the scorer.
- pytest is a dev dependency only, pinned in `evals/requirements-dev.txt` to `pytest==9.1.1` (released 2026-06-19). 9.2.0 was one day old when this was decided, under the project's two-week rule. It is installed in `evals/.venv`, which is git-ignored.

### Amendment, 2026-11-03: the healthy truth is final

`odd/tasks/lab-realism-min.md` T5 fixed the format of healthy recordings, so `NONE|NONE` is no longer provisional.
- `verify-agent.sh --schedule` runs a healthy control of holdout-2's schedule exactly as a fault case, with no injection, and writes `NONE|NONE` as its truth in its score file and `summary.tsv`.
- `record-replay.sh` copies that truth into the control's manifest row, whose fault is `healthy`.
- The scoring rule above is unchanged: `UNKNOWN|UNKNOWN` is correct on a healthy case, any cause a false alarm, no report a miss. `verify-agent.sh`'s live verdict applies the same rule.

Why final now: holdout-2 is recorded after the freeze and read once, so its healthy rows must carry the token the scorer reads. Changing it after a recording would mean rewriting a holdout's manifest.

## Alternatives considered

- **pandas and scipy for the statistics**: shorter code, but an install for every measurement and two large dependencies for a few formulas.
- **The normal approximation and chi-squared McNemar**: standard, but wrong on n = 5 to 15 and on 9 discordant cases.
- **Scoring in Java, next to the replay driver**: one toolchain, but against ADR-001 and harder to rerun on committed results alone.
- **Counting each repetition as a case**: more apparent data, but correlated repetitions would make intervals look narrower than they are.

## Consequences

### Positive

- Every claim carries an interval and a paired test, and its plan predates it.
- Results are committed and regenerate without the lab or a model.
- The scorer runs anywhere Python 3.12 runs.

### Negative

- Hand-written statistics need their own tests; the tests pin the vision's quoted numbers.
- A plan per run adds a commit before each measurement.
- The healthy token may change with follow-up 10, and the scorer with it. (Closed by the amendment of 2026-11-03.)
