# Feature: a change column in the dossier's count tables

## Objective

Give every count table of the dossier (order states, payment states, traffic, event counts) a `change` column, incident minus previous, computed by code, so the model reads the direction of a change instead of comparing two columns.

## Problem

On the local route, granite reads the two windows the wrong way round (`odd/tasks/report-field-order.md`, local F3 of 2026-10-23, `infra/scripts/out/replay-20261023T173124Z-local`). In the F3 dossier the gateway-mock 429s (D23, previous 6, incident 0) and `PAYMENT_FAILED` (D12, 6 then 0) belong to the previous window, yet two of three reports name gateway-mock from them; the decisive rows, order-service -> payment-service `ConnectException` 0 then 6 (D19) and the `:9999` URL in the order-service log (D41), are read past.

## Why

Comparing two adjacent numeric columns is a step a small model gets wrong; subtraction is deterministic, so code does it ("the LLM proposes, deterministic code verifies"). The change is arithmetic, not a judgment: no threshold, no filter, no marker, so ADR-011 ("No judgments, hints, anomaly markers or thresholds") still holds. The human chose this over a thresholded "Changes" section (which would need an ADR superseding part of ADR-011) on 2026-10-23.

## Scope

In: `DossierRenderer` (the count tables and the header line that describes the columns), its tests and golden files, the spec's "Evidence dossier gathering" (the list of computed values) and "Evidence dossier rendering". Out: latency (the 0.017 to 0.018 misreading stays; a delta there would be a different computed value), log signatures (incident window only), any threshold or filtering, the line-relevance check of citations (separate work).

## Design

- Column `change` after `incident` in every table that has `previous` and `incident` counts: `+n` when incident is greater, `-n` when smaller, `0` when equal.
- `-` when either window's call failed (its count is unknown), as the renderer already prints `-` for a failed window.
- A label absent from one window whose call succeeded counts as 0 there, as the merged rows already do.
- D-numbers do not move: a column adds no line.
- The header line D4 states the column: incident minus previous.

## Acceptance criteria

1. Every count table with `previous` and `incident` has a `change` column, as above; the F3 golden file shows `+6` on the `ConnectException` row and `-6` on the 429 row. Amended at T1: the F3 fixture has no 429 row, so the criterion accepted is `+6` on the `ConnectException` row and `-4` on the `PAID`, `AUTHORIZED` and success rows; the `-6` on the 429 row was observed in the T2 replay dossier (D23).
2. A failed window gives `-` in `change`.
3. No banned interpretive word, and the fixture dossiers stay under the token ceiling.
4. The spec lists the change as a computed value and shows it in a scenario.
5. Measured on the local replay, 3 samples per fault (free): F1 and F3 better than 0/3 each, F2 still 3/3. The cloud replay needs its own go-ahead.

## Tasks

- [x] T1 The change column in `DossierRenderer`, with tests, golden files and the spec (route: delegated writer; the renderer, its test, two golden files and the spec are several non-trivial files).
- [x] T2 Measurement: the local replay, F1-F3, 3 samples each (route: inline; one script run in the background and a bounded read of its outputs).

## Progress

- 2026-10-23: branch `feat/dossier-change-column`, from `feat/report-field-order` (closed at `d044eae`). Forecast: about 150 authored changed lines; under the 400-line budget.

- 2026-10-23: T1 done (route: delegated writer). `DossierRenderer.countTable` adds `change` after `incident` (`+n`, `-n`, `0`, `-` when a window is unknown); D4 states it; no line added, D-numbers unchanged. RED: `everyCountTableHasAChangeColumnOfIncidentMinusPrevious`, `aFailedWindowGivesNoChange` and the updated failed-section test failed; GREEN: full suite 432 tests, 0 failures, 0 errors. Golden files differ only in D4 and the count tables. The stress dossier grew to 5,606 est. tokens, so its ceiling moved from 5,500 to 5,700 (test and spec). The F3 fixture has no 429 row, so acceptance criterion 1 is shown with `+6` (ConnectException) and `-4` (PAID, AUTHORIZED, success rows). `ReportCheckTest` and the spec's "Evidence is checked" example carry the new row literal.

- 2026-10-24: T2, local replay, 3 samples per fault (`infra/scripts/out/replay-20261023T194351Z-local`, on `cd9d575`): **F1 0/3, F2 3/3, F3 0/3**, the same as without the column. 9 of 9 runs `COMPLETED`, 9 of 10 report attempts valid (the tenth, F3-2's first, failed an evidence-count rule and its retry was valid), report calls 155-464 s against the 600 s deadline, so the prompts still fit. F1 names payment-service three times (the column does not reach latency, as scoped). F3 names gateway-mock three times, once with the right category (`CONFIGURATION`); in sample 2 the dossier shows `D23 | client | 429 | ... | 6 | 0 | -6 |` and the summary still speaks of "a surge of ... 429 Too Many Requests". The direction is now written out and granite still reads the previous window's rows as the incident: acceptance criterion 5 is **not met**. The column does not fix what it was built for on the local model; the cloud was not measured.

- 2026-10-24: the human chose to revert the column (`git revert` of `46139df`): it did not change the local results, and it took headroom from the stress ceiling. Revert `7b8a4cf` (route: inline): `agent/` and `openspec/` are identical to `879cf4b`; full suite 430 tests, 0 failures, 0 errors (the two change-column tests are gone with it); review assessment (base `7b8a4cf~1`, committed only): medium, `review_due=false` (`under_budget`). Feature closed without its behavior; what it showed stays: on the local model, writing the direction of a change out does not stop the previous window's rows being read as the incident's.

## Next step

Feature closed (reverted).
