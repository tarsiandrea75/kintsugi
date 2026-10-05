# Setup — Windows 11

Environment checklist for local development. Shell: Git Bash or PowerShell. Last verified: 2026-10-05.

Pinned versions and runtime defaults are recorded in [ADR-006](adr/ADR-006-stack-versions.md). Use the minimums below.

## Tools

- [ ] **Java 21** (LTS)
  - Verify: `java -version`
  - Install: `winget install Microsoft.OpenJDK.21` (or any JDK 21 distribution)
- [ ] **Maven** (optional)
  - The project ships the Maven Wrapper in `agent/`: use `./mvnw` (Git Bash) or `mvnw.cmd` (PowerShell), so a local Maven is not required.
  - Verify (only if installed): `mvn -v`
  - Install: `winget install Apache.Maven` (or download from maven.apache.org and add `bin` to `PATH`)
- [ ] **Docker Desktop**
  - Verify: `docker version`
  - Install: `winget install Docker.DockerDesktop` (WSL 2 backend)
- [ ] **Ollama >= 0.4.6**
  - Verify: `ollama --version`
  - Install: `winget install Ollama.Ollama`
  - Why 0.4.6: streaming responses with tool calling need it (non-streaming tool calling works from 0.2.8).
- [ ] **Python 3** (for `evals/` only)
  - Verify: `python --version`
  - Install: `winget install Python.Python.3.12` (or any current Python 3)

## Local model

Hardware: laptop with 32 GB RAM and no GPU, so quantized ~8B models.

| Role | Model | Notes |
|------|-------|-------|
| Recommended | `granite4.1:8b` | Built for tool calling and RAG, 128K context, Apache 2.0 |
| Alternative | `qwen3:8b` | Well documented with Spring AI; thinking mode is slow on CPU unless disabled |
| Alternative | `qwen3.5:9b` | Newer and larger; less proven for tool calling |

Status: **pending smoke test**. The choice is confirmed only after running about 10 tool-calling prompts against each candidate.

- [ ] Pull the recommended model: `ollama pull granite4.1:8b`
- [ ] Pull alternatives when testing: `ollama pull qwen3:8b`, `ollama pull qwen3.5:9b`
- [ ] Run the ~10-prompt tool-calling smoke test and record the result

## Build and run

Run from `agent/` (use `mvnw.cmd` on PowerShell):

| Goal | Command | Needs |
|------|---------|-------|
| Unit tests | `./mvnw test` | Nothing (offline, no Ollama) |
| Live test | `./mvnw test -Plive` | Ollama running with `granite4.1:8b` (about 30–45 s) |
| Demo | `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` | Ollama running with `granite4.1:8b` |

If Ollama is down, the demo prints `Ollama not reachable at <url>. Start it with: ollama serve` and exits with code 1, without a stack trace.
