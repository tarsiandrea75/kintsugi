# ADR-001: Java/Spring for the system, Python only for evals

## Status

Accepted

## Date

2026-10-05

## Context

Kintsugi is a local-first incident-diagnosis agent that investigates a lab of microservices broken on purpose by a fault injector. The author is a senior backend engineer with 20+ years of production experience in Java. Target roles value AI integration inside real backend stacks. The evaluation harness needs data analysis and scoring tooling, where the Python ecosystem is strongest.

## Decision

- The system (lab services, fault injector, agent, MCP tools) is written in Java with Spring Boot and Spring AI.
- Python is used only for the evaluation and benchmark code under `evals/`.
- Tool and library versions are not recorded here. They go in a separate ADR written when the stack is first pinned.

## Alternatives considered

- **Python for everything**: faster access to AI libraries, but discards the author's main strength and does not mirror typical company backends.
- **Java for everything, including evals**: one language, but weaker data analysis and plotting tooling for the benchmark.
- **Another JVM or typed language for the agent**: no clear benefit over Spring AI for this scope.

## Consequences

### Positive

- Leverages existing skills and mirrors real company setups.
- Python stays small and isolated, limited to scoring and reporting.

### Negative

- Two toolchains to install and maintain.
- Some AI libraries appear in Python first, so Java features may lag.
- A contract between the system and the evals (data formats) must be kept explicit.
