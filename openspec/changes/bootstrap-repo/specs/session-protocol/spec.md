# Session Protocol Specification

## Purpose

Rules for starting and ending a working session, the `STATE.md` record, and journal entries. Verified by structural readback.

## Requirements

### Requirement: Session start

`CLAUDE.md` MUST instruct the agent at session start to read `docs/STATE.md` and the latest journal entry, report in Italian, propose the next action, and ask for confirmation before proceeding.

#### Scenario: Start steps documented
- GIVEN `CLAUDE.md` exists
- WHEN its session section is read
- THEN all four start steps appear

### Requirement: Session end

`CLAUDE.md` MUST instruct that on session end or the phrase "chiudiamo" the agent overwrites `docs/STATE.md` and adds a journal entry.

#### Scenario: End trigger documented
- GIVEN `CLAUDE.md` exists
- WHEN its session section is read
- THEN the "chiudiamo" trigger, the STATE overwrite, and the journal entry are stated

### Requirement: STATE.md fields

`docs/STATE.md` MUST contain: milestone, last action, next action, open questions, blockers. The next action MUST read exactly "Monday: Spring AI hello-world calling one tool with the local model" and refers to the next session on 2026-10-05. Open questions MUST include the unassigned number of the versions ADR (decided when it is written).

#### Scenario: All fields present
- GIVEN `docs/STATE.md` exists
- WHEN its headings are read
- THEN all five fields are present

#### Scenario: Exact next action
- GIVEN `docs/STATE.md` exists
- WHEN the next action is read
- THEN it equals the exact text above

#### Scenario: Versions ADR open question
- GIVEN `docs/STATE.md` exists
- WHEN open questions are read
- THEN the versions ADR number is listed as undecided

### Requirement: Journal entry

`docs/journal/2026-10-05-01.md` MUST exist, named `YYYY-MM-DD-NN.md`, and MUST summarize the bootstrap work.

#### Scenario: Journal present and named correctly
- GIVEN the change is applied
- WHEN `docs/journal/` is listed
- THEN `2026-10-05-01.md` exists, is non-empty, and the planning entry is unchanged

### Requirement: Record hierarchy

`CLAUDE.md` MUST state that the Markdown files are the source of record and Engram is only a convenience.

#### Scenario: Hierarchy stated
- GIVEN `CLAUDE.md` exists
- WHEN it is read
- THEN the Markdown-over-Engram rule is present
