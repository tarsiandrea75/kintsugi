# Feature: Origin and Host checks on the agent and fault-injector APIs

## Objective

Give the agent's incident API (`127.0.0.1:18086`) and the fault-injector's API (`127.0.0.1:18084`) the same two checks mcp-tools has on `/mcp` (ADR-004): a request with an `Origin` header is rejected with 403, and a request whose `Host` is outside an allow-list is rejected with 421.

## Problem

The security review of 2026-10-24 (branch `feat/dossier-change-column` against `main`) found that both APIs, bound to loopback with no authentication, have neither check. A web page the human opens can reach them by DNS rebinding (its own hostname resolving to 127.0.0.1) or by a cross-site "simple" request: the agent's `POST /incidents` takes `@RequestBody String`, so a `text/plain` POST is accepted and starts an incident (on the cloud route it spends API credit), and a body-less `POST /faults/{id}/restore` needs no preflight. Rebinding also reads incident JSON, including `?full=true` lab tool outputs. Rated 3/10 as a vulnerability (the threat model accepts loopback-only access), fixed as defense in depth at the human's request.

## Why

Loopback binding stops other hosts, not the human's own browser. Both callers of these APIs are scripts and server processes (`curl`, the verify scripts), which send no `Origin`; a request with one comes from a browser. The `Host` allow-list closes DNS rebinding, where the browser sends the attacker's hostname. The same rule in three services is one rule to explain.

## Scope

In: a servlet filter (or Spring `OncePerRequestFilter`) in `agent` and in `lab/fault-injector`, an allow-list property per service with a default for compose and the host port, startup failure on an empty list (as ADR-004), tests, the agent and fault-injector specs, ADR-004's text if it states the rule for mcp-tools only. Out: authentication, TLS, mcp-tools (already done), `infra/.env.example` (the human's file).

## Design

- `Origin` present (any value) → 403, before the controller. No `Origin` → the `Host` check.
- `Host` not in `kintsugi.<service>.allowed-hosts` (comma-separated, exact match, case-insensitive host) → 421.
- Defaults: the in-compose name and port, plus `localhost:<host port>` and `127.0.0.1:<host port>`. Every in-repo caller (scripts, other services, the agent's own clients) must still pass: the writer lists them.
- An empty or missing list stops the startup, so the check cannot be switched off by accident.
- Actuator endpoints are covered too, unless a compose healthcheck or Prometheus scrape uses a Host outside the list; then that Host is in the default list.

## Acceptance criteria

1. On both APIs: a request with `Origin` gets 403; a request with `Host: evil.example:18086` (or `:18084`) gets 421; a request with an allowed Host and no Origin works as before.
2. An empty allow-list fails the startup with a message naming the property.
3. Every in-repo caller still works: the full test suites of both services pass, and the callers found are listed in the progress.
4. The specs state the rule with scenarios.

## Tasks

- [ ] T1 The guard in agent and fault-injector, with tests and specs (route: delegated writer; two services, filters, config, tests and two specs).

## Progress

- 2026-10-24: branch `feat/loopback-api-guard`, from `feat/dossier-change-column` (`5711299`). Forecast: about 300 authored changed lines; under the 400-line budget.

## Next step

T1.
