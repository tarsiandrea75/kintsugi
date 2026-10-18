# ADR-012: A cloud model for design iterations, the local model for verification

## Status

Accepted by the human on 2026-10-18, with `claude-haiku-5-5` as the development model. No standing budget: each batch of cloud runs still needs the human's go-ahead.

## Date

2026-10-18

## Context

Every full `verify-agent.sh` run answers two questions at once:

1. **Does the agent's design work?** The modes (explorer or dossier), the prompts, the deterministic checks, the report schema.
2. **Can a local ~8B model on this laptop's CPU do it?** This is M2's check: "F1–F3 correctly diagnosed with a local model" (`docs/vision.md`).

On this host the second question hides the first one. The T6 comparison of ADR-011 (`odd/tasks/evidence-dossier.md`, 2026-10-18) scored explorer 3/9 and dossier 0/9. Eight of the nine dossier misses never produced a diagnosis: five timed out at the 420 s call deadline and three reports were cut at 512 tokens. The first `ACT` call of a phase took a median of 342 s in dossier mode against 145 s in explorer mode. So the runs measured the CPU's prompt-reading speed, not whether the dossier helps the model diagnose. Each run takes about 2 to 3 hours. With 3 runs per mode the samples are too few to separate design effects from noise.

The vision already plans for this. Its risks table has "Hardware too slow for local models", signal "more than a minute per agent step", plan B "develop with a cheap API, local only for benchmark". Agent steps here take 66 to 388 s. Section 4 keeps "one commercial provider, as baseline and fallback". ADR-003 deferred choosing that provider to M2, and M2 is now.

## Decision

- **Two routes, two jobs.**
  - The *development route* is a cloud model. It is used to iterate on the agent's design: modes, prompts, checks, schema.
  - The *verification route* is the local model (`granite4.1:8b` on Ollama, ADR-003). It is the only route that can pass M2's check or any benchmark claim about local models.
  - A design change is accepted only after it is confirmed on the verification route.
- **Provider:** Anthropic, through Spring AI's Anthropic module (`spring-ai-starter-model-anthropic`). Spring AI 2.0.1 is the latest GA release (checked on Maven Central, 2026-10-18; `2.1.0-M1` is a milestone) and is already the project's BOM. Its `AnthropicChatOptions` supports a JSON output schema (`outputSchema`), `effort` and tool calling, so `ModelRoute` gets a second implementation and the loop stays the same.
- **Model:** `claude-haiku-5-5`, chosen by the human. It is the cheapest current model, at $0.10 input and $0.50 output per million tokens for prompts up to 100K (prices from Anthropic's model table, cached 2026-10-06). The alternatives in the same family are `claude-sonnet-5-5` ($2 / $10) and `claude-opus-5-5` ($4 / $20).
- **The selection is configuration, like the mode.** A property such as `kintsugi.agent.route` (`local` or `cloud`) is read at startup. It is recorded on the incident and the report next to `mode`, so a score always says which model produced it.
- **The verification route stays the default.** `verify-agent.sh` runs it unless told otherwise. The cloud route needs an explicit flag and an API key in `infra/.env`, never in the repository.

## Alternatives considered

- **Keep iterating on the local model only.** This is honest about M2 but costs 2 to 3 hours per sample. The T6 results show it measures latency first. Rejected as the *development* loop; kept as the *verification* loop.
- **Raise the call deadlines and the report cap until dossier mode fits.** This is cheap to try. But the 45 min incident budget already does not hold the worst case (agent-loop follow-ups), and it still leaves one sample every few hours. It stays a possible tuning step on the verification route, not a way to iterate.
- **A replay harness** (an open decision since runs 11-13): record the tool outputs of an incident once, then replay only the model calls, with no lab and no 11 minute spacing between faults. It is complementary, not an alternative: it removes the lab's wall-clock, and the cloud route removes the model's. Together they make one sample take minutes. A candidate next feature after this ADR, with its own design section.
- **An hourly cloud GPU running the same open-weight model** (the vision's other plan B). It keeps the exact model, so results transfer fully. But it adds infrastructure, a provider account and per-hour cost for a project that stays local. Not chosen now; it becomes the better option if cloud-model results turn out not to transfer.
- **A bigger local model (vision configuration B, ~14B).** It would be slower on this CPU, not faster.

## Consequences

### Positive

- **Feedback in minutes, not hours.** More samples per fault give results that can separate a design effect from noise.
- **Isolated design questions.** Does the dossier help a model diagnose F1–F3, apart from how fast this CPU reads it?
- **The API baseline arrives.** The vision's configurations C and D (hybrid, all-API) need it, and ADR-003 had deferred it.
- **Low cost (estimated, not measured).** An explorer incident makes about 20 model calls. At about 5k prompt tokens and 300 answer tokens each, that is about 100k input and 6k output tokens, about $0.01 per incident on `claude-haiku-5-5`, or a few cents with thinking tokens. A 9-incident comparison is well under $1; on `claude-opus-5-5` it is about $5. The real cost comes from `usage` in the responses and is recorded with each run.

### Negative

- **Results do not fully transfer.** A stronger model can succeed where the 8B fails and hide a design weakness, for example a prompt the small model misreads. Only the verification route decides M2, and every accepted change is re-run there.
- **No temperature 0.** On `claude-haiku-5-5` and `claude-sonnet-5-5` a non-default sampling value is rejected (HTTP 400), so the cloud route cannot send the local route's `temperature: 0`. Thinking is on by default; it can be disabled only at `effort` `high` or below. Answers vary more from run to run, which the larger sample count must absorb. The route's options must leave `temperature` unset.
- **Data leaves the machine.** Prompts carry the lab's tool outputs, logs and runbooks. All of it is synthetic: neutral customer ids, no personal data, no secrets. The API key is a new secret, kept in `infra/.env` (git-ignored). Recorded so that no real data is ever routed this way.
- **A second route to maintain.** It needs its own `ModelRoute` options and call deadlines, its own budgets for API latency, an API key and network access. Tests use the scripted fake chat model as today; only live runs reach the API.
- **New spend.** Every cloud run costs money, however little. Each batch of runs needs the human's go-ahead and a recorded cost.
