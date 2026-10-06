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
| Default | `granite4.1:8b` | Built for tool calling and RAG, 128K context, Apache 2.0 |
| Alternative | `qwen3:8b` | Practically tied in the smoke test; needs thinking disabled to stay fast on CPU |
| Not chosen | `qwen3.5:9b` | Passed the smoke test but more than twice as slow |

Status: **chosen**. All three candidates passed the 10-prompt tool-calling smoke test; see [ADR-003](adr/ADR-003-local-models-default.md) and the [reports](evidence/smoke-test/).

- [ ] Pull the default model: `ollama pull granite4.1:8b`
- [ ] Pull alternatives when testing: `ollama pull qwen3:8b`, `ollama pull qwen3.5:9b`
- [x] Run the 10-prompt tool-calling smoke test and record the result

Smoke test for one model (from `agent/`):

```bash
./mvnw test -Plive -Dtest=LiveToolCallingSmokeTest -Dkintsugi.model=<model>
```

- qwen models also need `-Dspring.ai.ollama.chat.think=false`.
- The report is written to `agent/target/smoke-test/`.

## Build and run

Run from `agent/` (use `mvnw.cmd` on PowerShell):

| Goal | Command | Needs |
|------|---------|-------|
| Unit tests | `./mvnw test` | Nothing (offline, no Ollama) |
| Live tests | `./mvnw test -Plive` | Ollama running with `granite4.1:8b`; includes the smoke test, about 3–7 min per model |
| Demo | `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` | Ollama running with `granite4.1:8b` |

If Ollama is down, the demo prints `Ollama not reachable at <url>. Start it with: ollama serve` and exits with code 1, without a stack trace.

## order-service

`lab/order-service` is a standalone Maven project with its own wrapper. Build and test it with:

```bash
cd lab/order-service && ./mvnw test
```

- Docker must be running: the persistence and end-to-end tests start `postgres:18.6` through Testcontainers.
- The stack and versions are recorded in [ADR-007](adr/ADR-007-lab-persistence-stack.md), and the behavior in the [living spec](../openspec/specs/order-service/spec.md).

## payment-service

`lab/payment-service` is a standalone Maven project with the same stack as order-service. It listens on port 8081. Build and test it with:

```bash
cd lab/payment-service && ./mvnw test
```

- Docker must be running, for the same Testcontainers reason as order-service.
- The behavior is in the [living spec](../openspec/specs/payment-service/spec.md).

## Run order-service and payment-service together

Docker Compose for the whole lab comes on Friday (week 1). Until then, run each service by hand, each with its own PostgreSQL database.

1. Start one PostgreSQL 18.6 container per service:

   ```bash
   docker run -d --name orders-db   -e POSTGRES_DB=orders   -e POSTGRES_USER=lab -e POSTGRES_PASSWORD=lab -p 5432:5432 postgres:18.6
   docker run -d --name payments-db -e POSTGRES_DB=payments -e POSTGRES_USER=lab -e POSTGRES_PASSWORD=lab -p 5433:5432 postgres:18.6
   ```

2. Start payment-service on 8081 (Flyway creates its schema on startup):

   ```bash
   cd lab/payment-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5433/payments --spring.datasource.username=lab --spring.datasource.password=lab"
   ```

3. In another shell, start order-service on 8080:

   ```bash
   cd lab/order-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5432/orders --spring.datasource.username=lab --spring.datasource.password=lab"
   ```

4. Place an order. With the fake gateway it ends in `PAID`:

   ```bash
   curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' \
     -d '{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": 2}]}'
   ```

   Stop payment-service and repeat: the response is still 201, the order stays `PAYMENT_PENDING`, and order-service logs a `WARN` line with the order id. That is the symptom of fault F1.

order-service reaches payment-service through these properties (set them like the datasource ones above, or as environment variables such as `KINTSUGI_PAYMENT_READ_TIMEOUT`):

| Property | Default | Meaning |
|----------|---------|---------|
| `kintsugi.payment.base-url` | `http://localhost:8081` | payment-service root URL |
| `kintsugi.payment.connect-timeout` | `1s` | longest wait to open a connection |
| `kintsugi.payment.read-timeout` | `3s` | longest wait for the response once the request is sent |
