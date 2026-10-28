# Kintsugi

Local-first AI agent that diagnoses incidents in a microservices lab broken on purpose by a fault injector. Every diagnosis is scored against ground truth.

- Vision and plan: [docs/vision.md](docs/vision.md)
- AI-engineering concepts: [docs/concepts.md](docs/concepts.md)
- Environment and model setup: [docs/setup.md](docs/setup.md)

## Sources of truth

Order: `docs/vision.md` > `docs/STATE.md` > latest `docs/journal/` entry > Engram.

- Repo Markdown is the source of record. Engram is only a convenience and may be wrong or missing.
- If a spec or request contradicts `docs/vision.md`, raise it with the human before proceeding.

## Session protocol

Start of every session:

1. Read `docs/STATE.md`.
2. Read the latest entry in `docs/journal/`.
3. Tell the human, in Italian, where we are and what is next.
4. Propose the next action and ask for confirmation before starting.

End of session, or when the human says "chiudiamo":

1. Overwrite `docs/STATE.md` with: milestone, last completed action, next action, open questions, blockers.
2. Add `docs/journal/YYYY-MM-DD-NN.md` (NN = per-day counter): what we did, decisions and why, links to specs, ADRs and commits.
3. Commit them, then run `infra/scripts/backup-bundle.sh` and report its output line. Also run it whenever a milestone or feature closes.

## Working conventions

- Use ODD with living specs for every feature, in small changes (400-line review budget):
  - Track each feature in `odd/tasks/<feature>.md` with acceptance criteria written before the code, and write tests first.
  - Every feature that adds or changes behavior includes a task to create or update its living spec in `openspec/specs/<capability>/spec.md` (requirements plus GIVEN/WHEN/THEN scenarios). The spec is the readable contract of what the system does; tests prove it.
  - A new service or component gets a short design section in the feature document before any code, or an ADR when the choice is significant.
  - Why: ODD keeps the workflow light; the living specs keep the contracts the evals depend on (fault catalog, fault-injector, service APIs) readable and reviewable.
- Explain the "why" of each AI-engineering concept and step, not only the "how".
- Before choosing a library or model, check its current version and record the decision in an ADR (copy the headings of `docs/adr/ADR-001-java-spring-system-python-evals.md`).
- Every measurement starts from a plan committed before the run, reports Wilson 95% CIs and commits its results under `evals/results/`.
- A holdout is read once; Config 0 runs alongside every measurement.
- Core rule: the LLM proposes, deterministic code verifies at a stated level, the policy authorizes. A rules-only configuration diagnoses from the same facts, so the LLM's contribution is a number.

## Language

Conversation in Italian. Everything written to the repo (code, docs, specs, commits) in English.

Global user rules apply; this file adds project context only.
