# Repo Structure Specification

## Purpose

Required top-level files, folders, and placeholders so every session has a stable entry point. Verified by structural readback.

## Requirements

### Requirement: Root entry files

The repo MUST contain `CLAUDE.md`, `README.md`, and `.gitignore` at the root.

#### Scenario: Entry files present
- GIVEN the bootstrap change is applied
- WHEN the root is listed
- THEN all three files exist and are non-empty

### Requirement: CLAUDE.md content

`CLAUDE.md` MUST link to `docs/vision.md`, `docs/concepts.md`, and `docs/setup.md`, and MUST state: Markdown is the record and Engram is convenience; ODD with living specs in `openspec/specs/`, in small changes; explain the AI-engineering "why"; check current versions and record them in an ADR; conversation in Italian, files in English. It MUST NOT duplicate global user rules.

#### Scenario: Links resolve
- GIVEN `CLAUDE.md` exists
- WHEN each relative link is resolved
- THEN every target file exists

#### Scenario: Conventions stated
- GIVEN `CLAUDE.md` exists
- WHEN it is read
- THEN each convention above is present

### Requirement: README

`README.md` MUST contain the vision pitch, a work-in-progress notice, and a link to `docs/setup.md`.

#### Scenario: README complete
- GIVEN `README.md` exists
- WHEN it is read
- THEN pitch, notice, and a resolving `docs/setup.md` link are present

### Requirement: Setup checklist

`docs/setup.md` MUST list Windows 11 verify/install steps for Java 21, Maven, Docker, Ollama, and Python 3, and MUST name `granite4.1:8b` as the chosen model per ADR-003, with `qwen3:8b` as the documented alternative.

#### Scenario: Tools and model listed
- GIVEN `docs/setup.md` exists
- WHEN it is read
- THEN all five tools, the chosen model, the documented alternative, and a link to ADR-003 appear

### Requirement: ADR-001

`docs/adr/ADR-001-java-spring-system-python-evals.md` MUST exist with status, context, decision, and consequences sections, and MUST NOT state tool or library versions.

#### Scenario: ADR shape
- GIVEN the ADR file exists
- WHEN its headings are read
- THEN all four sections are present and no version numbers appear

### Requirement: Folder placeholders

An empty `.gitkeep` MUST exist in `lab/`, `infra/`, `agent/`, `mcp-tools/`, `evals/`, `ui/`, `openspec/specs/`, and `openspec/changes/archive/`.

#### Scenario: All placeholders present
- GIVEN the change is applied
- WHEN the eight paths are checked
- THEN each `.gitkeep` exists

### Requirement: .gitignore coverage

`.gitignore` MUST cover Java/Maven, Python, IDE, and OS files, plus `.env`, `.codegraph/`, and `.atl/`.

#### Scenario: Patterns present
- GIVEN `.gitignore` exists
- WHEN it is read
- THEN each category and the three named entries are present

### Requirement: Scope boundaries

The change MUST NOT modify `docs/vision.md`, `docs/concepts.md`, or `docs/journal/2026-10-03-00-planning.md`, and MUST NOT add code, build files, `docs/architecture.md`, `docs/runbooks/`, `docs/benchmark.md`, `.github/workflows/`, or a versions ADR.

#### Scenario: Sources untouched
- GIVEN the pre-change content of the three source docs
- WHEN compared after apply
- THEN they are byte-identical and no out-of-scope path exists
