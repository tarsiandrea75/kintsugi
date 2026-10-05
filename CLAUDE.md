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

## Working conventions

- Use SDD for every feature, in small changes (400-line review budget).
- Explain the "why" of each AI-engineering concept and step, not only the "how".
- Before choosing a library or model, check its current version and record the decision in an ADR (copy the headings of `docs/adr/ADR-001-java-spring-system-python-evals.md`).
- Core rule: the LLM proposes, deterministic code verifies, policy authorizes.

## Language

Conversation in Italian. Everything written to the repo (code, docs, specs, commits) in English.

Global user rules apply; this file adds project context only.
