# Freeway Ext 1.5.6 Release Notes

> Version: 1.5.6 | Highlight: **A Consul service-discovery adapter, and every module's configuration keys now declared to the core's unknown-key check.**

1.5.6 ships on the core 1.5.6 train: the parent version and `freeway.version` both moved
to `1.5.6`, so this repo builds against, and is versioned with, the core it extends. The
headline is `freeway-cloud-consul` — `ServiceRegistry` and `ServiceDiscovery` bound
`.primary()` against Consul's agent API, implemented with the JDK `HttpClient` alone. It
is the first production service-discovery backend in this repo, and the non-Kubernetes
counterpart to a K8s adapter. Alongside it, every adapter now declares its configuration
keys to the core's vocabulary check, and the benchmark migrated to core 1.5.6's
module-list entry points.

## Module dependencies

| Module | Third-party dependency |
|---|---|
| `freeway-cloud-consul` | **none** — Consul's agent HTTP API is reached with the JDK `HttpClient` |
| `freeway-http-jetty` | Jetty 12.1.13 |
| `freeway-http-undertow` | Undertow 2.4.3.Final |
| `freeway-mq-kafka` | Kafka Clients 4.3.1 |
| `freeway-db-hikari` | HikariCP 7.1.0 |
| `freeway-benchmark` | JMH 1.37, robaho-httpserver 1.0.29, sqlite-jdbc 3.53.4.0, H2 2.5.250 |

All modules track core `1.5.6`.

## Highlights

### A Consul service-discovery adapter (freeway-cloud-consul)

`ConsulModule` binds `ServiceRegistry` and `ServiceDiscovery` **primary**, replacing core's
`@Local` in-process defaults, so a non-Kubernetes deployment gets real cross-process
discovery. It implements the four seam operations and nothing else: the core's
`RegistryLifecycleHook` still registers, renews, re-registers on a lost lease and
unregisters; its `RegistryHealthContributor` deactivates itself once the active binding is
no longer `@Local`; and `drainWindow()` (default `PT5S`) feeds
`freeway.cloud.registry.shutdown-drain=auto`. **No third-party client** — the agent's HTTP
API is reached with the JDK `HttpClient`.

Two decisions are worth knowing because they are silent when wrong. `Endpoint.scheme` and
`basePath` travel in Consul `Meta` under reserved `freeway-*` keys, because the outbound
URL is rendered from all four endpoint fields — dropping them would silently mis-call an
https service or a path-prefixed one (the keys carry hyphens, not dots: Consul rejects a
`Meta` key containing `.`). And `renew` distinguishes "the agent no longer holds it"
(404 → `false`, which the core reads as "re-register me") from "could not determine" (any
other non-2xx → throws, so the core marks the node unhealthy instead of looping a
re-registration that will fail the same way).

Config keys are `freeway.cloud.consul.{agent-host,agent-port,scheme,token,ttl,drain-window}`,
declared in the module's `ConfigKeys` and contributed to the unknown-key vocabulary.

**Verified against a live agent.** The gated `ConsulIntegrationTest`
(`FREEWAY_TEST_CONSUL`, default `127.0.0.1:8500`) runs an adapter round-trip and a real
`FreewayApp` whose lifecycle hook registers and deregisters through Consul; against a
Consul 2.0.4 dev agent it is 13/13 green, skipped without the variable and red against a
dead address. Four behaviours a stub could not catch were found this way — Meta keys
cannot contain dots, a TTL check starts critical, the check id must be explicit, and
errors must carry the agent's response body — and are recorded in
[`consul-adapter-design.md`](consul-adapter-design.md).

### Every module declares its configuration keys (all modules)

Undertow, Jetty, HikariCP and Kafka now spell their keys in a nested `ConfigKeys` table
(full literals, the core 1.5.6 style) and contribute it from `bind(Binder)`
(`contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, prefix))`), like the core
modules do. The gap this closes runs both ways: a correctly configured ext key used to sit
outside every vocabulary, and a typo near one could not be suggested. `freeway.kafka.*`
becomes a declared namespace (twelve keys harvested from `KafkaConfig`; `freeway.kafka.lifecycle`
stays out — hook ids are identity strings, not keys). Undertow and Jetty share
`freeway.http` with the core (both own `freeway.http.websocket.max-frame-size`) and Hikari
shares `freeway.db`; a vocabulary may share a prefix, and the table must agree with it. A
pinning test per module keeps the vocabulary covering every key the module reads.

### Benchmark follows core 1.5.6's composition entry points (freeway-benchmark)

Core 1.5.6 moved the composition tree (`ModuleNode`) behind `ioc.internal`, so
`FreewayApp.create(ModuleNode.app("freeway-benchmark", …))` became
`FreewayApp.create(BenchDbModule.class, DbModule.class, CliModule.class)`; the application
root's name is no longer an entry-point input, so the startup log's root line reads
`application`. The CLI also converged: the per-cell bookkeeping (write the run row, pick
the median round, record dispersion, apply the gates) lives once in `cli/BenchCell`,
shared by `run` and `suite`; `BenchRepository` is bound by `BenchDbModule`; and
`Command.Context` records which options a command read, so the dispatcher warns (never
fails) about a `--flag` nothing consumed — a typo can no longer silently keep a default
and measure a different cell.

## Corrections

- **The `freeway-http` `tests`-classifier dependency is removed.** It was declared from
  the initial import, when the shared engine contract tests lived in the core's test tree;
  `746e5b9` converged those contracts into this repo's `freeway-http-adapter-testkit`, and
  the declaration was left behind. Verified unused rather than assumed — all 61 top-level
  classes in the jar were cross-checked against every Java source here: zero references.
- The Consul module is formatted with the repo's `google-java-format` gate (it had been
  written in the core's 4-space style; this repo is 2-space, checked at `verify`).

## Migration

- `freeway.version` and the parent version are `1.5.6`; the reactor builds against core
  1.5.6.
- If you called `FreewayApp.create(ModuleNode…)` anywhere in benchmark-style code, it is
  now the module list; the root name is set by `FreewayApp.create(...).name(...)`, not by
  the tree.
- Nothing else in this repo changes shape for a consumer: the adapters keep their
  interfaces, and the Consul adapter is additive.

## Numbers

199 tests (7 skipped — 5 broker-gated, 2 Consul-agent-gated), 8 modules. The Consul
adapter is the one module with **no** third-party dependency; its design note is
[`consul-adapter-design.md`](consul-adapter-design.md), and its module's four collaborators
are package-private (only `ConsulModule` is public, since no public signature names the
others).
