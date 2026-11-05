# ADR-029: NETWORK and CODE_DEFECT as decoy categories

## Status

Proposed (2026-11-05). Feature: [`odd/tasks/decoy-categories.md`](../../odd/tasks/decoy-categories.md).

## Date

2026-11-05

## Context

- **The leakage.** The report's taxonomy is exactly the four catalog categories (`Causes.java:17-18`), so the model knows that the answer is one of them. `odd/tasks/vision-revision.md` names this structural leakage (row 4), and `docs/vision.md:55` asks for "decoy categories, so the taxonomy is wider than the catalog".
- **Where the categories came from.** `vision-revision.md` row 7 bundled NETWORK and CODE_DEFECT with the ranked report. The human split them out on 2026-11-03 (ADR-025, Context), because they change the taxonomy, its enums, the goldens and the prompt.
- **No definitions exist.** The repository names the two categories but never defines them.
- **No data has them as a truth.** The lab's catalog is F1–F4 (`openspec/specs/fault-injector/spec.md`). The RCAEval map (ADR-020, §5) sends cpu, mem, disk and socket to RESOURCE_EXHAUSTION, and delay and loss to DEPENDENCY_LATENCY. RE3, which has code-level faults, is not downloaded.

## Decision

1. **Two new report categories, as decoys.** The human chose this on 2026-11-05.
   - The enum becomes `DEPENDENCY_LATENCY`, `DEPENDENCY_REJECTING`, `CONFIGURATION`, `RESOURCE_EXHAUSTION`, `NETWORK`, `CODE_DEFECT`, `UNKNOWN`.
   - The report schemas, `ReportCheck`, `PlanCheck` and the ranked `alternatives` all read that one list.
2. **Their definitions in the prompt**, in the style of the existing ones:
   - `NETWORK`: the network between components drops, delays or blocks traffic, while the components at both ends do their own work normally.
   - `CODE_DEFECT`: a component's own code fails on valid requests. It throws errors or returns wrong results, although its settings, resources and dependencies are healthy.
3. **No truth changes.**
   - ADR-020's map stays as pre-registered, so delay and loss remain DEPENDENCY_LATENCY.
   - The fault-injector's `RootCauseCategory` keeps the catalog's four values.
   - `leakage.py` keeps the truth categories.
   - The committed results keep their truths and regenerate byte for byte.
4. **The rules are unchanged** (ADR-016). Config 0 still emits only the old categories. Its verdicts stay identical.
5. **Measured before the freeze.** A committed plan measures the new taxonomy on dev, with Config 0 alongside, against the committed runs of the same configuration.
   - The question is whether the decoys cost accuracy:
     - on the lab, scored on the full pair;
     - on RE2-OB, scored on the service, with the category reported beside it.
   - A model that picks a decoy is wrong by construction, so the cost shows directly.

## Alternatives considered

- **Move delay and loss to NETWORK.** This is closer to what those faults are. It amends a map that ADR-020 pre-registered, and it splits truths between old and new results. Service-only AC@1 would not change. Rejected by the human on 2026-11-05.
- **Leave the taxonomy as the catalog.** No cost and no risk, but the leakage that vision §1 and `vision-revision.md` name would stay. Rejected.
- **Decoys with no definition in the prompt.** A bare name gives the model nothing to weigh the evidence against, and every other category has a definition. Rejected.

## Consequences

### Positive

- The taxonomy is wider than the catalog, so naming a catalog category is no longer free.
- No truth, result or rule changes, so every comparison with earlier runs stays valid.

### Negative

- **The definitions overlap the cases the prompt already separates.** Two overlaps, both shown directly on the lab dev set:
  - **CODE_DEFECT and DEPENDENCY_REJECTING:** F2 is a component that answers with errors.
  - **NETWORK and the CONFIGURATION rule:** F3 is a component that cannot reach the component it calls. RE2-OB's loss cases could also draw NETWORK, which would cost category accuracy there but not service AC@1.
- **The change touches what the freeze pins.** The prompt, the schemas and their pin files change, so it must merge before 2026-12-01.
- **A decoy is never a right answer** in the data we hold. The categories only measure the cost of a wider choice, never the model's skill at recognising a network or code fault.
