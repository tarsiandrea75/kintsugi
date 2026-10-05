# Kintsugi

A local-first AI agent that diagnoses incidents in a microservices system.

Kintsugi is a local-first AI agent that diagnoses incidents in a microservices system. A fault injector breaks the system on purpose, so every diagnosis is scored against ground truth. The benchmark shows where small open-weight models are enough and where paying for a frontier API is worth it.

> **Work in progress.** Milestone M1 (the lab) runs from 2026-10-05 to 2026-10-25. There is no runnable code yet.

## Why

- **Local-first:** can a small company run a reliable incident-response agent without an API budget?
- **Ground truth:** the fault injector records the true root cause, so every injected fault is a scored test case.
- **Measured, not claimed:** a benchmark compares local models and frontier APIs on injected incidents.

## Getting started

See [docs/setup.md](docs/setup.md) for the environment checklist and the local model.

## Docs

- [Vision and plan](docs/vision.md)
- [Concepts](docs/concepts.md)
- [Current state](docs/STATE.md)
- [Architecture decisions](docs/adr/)
