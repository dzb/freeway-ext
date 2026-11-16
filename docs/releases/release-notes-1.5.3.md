# Freeway Ext 1.5.3 Release Notes

> Version: 1.5.3 | Aligned with Freeway core 1.5.3 | Tag: `v1.5.3`

1.5.3 is a **breaking release**. The adapters migrate along with core's API convergence; the compile errors are the migration path.
Across three rounds of adaptation since 1.5.0 (1.5.0 → 1.5.1 → 1.5.2/1.5.3), this covers the core framework's symbol chain convergence,
module tree value typing, entry factory unification, parameter matrix conversion to records, Kafka bridge fixes, and a full benchmark refactor.

## Module Dependency Versions

| Module | Freeway Core | Third-party libraries |
|--------|-------------|-----------------------|
| `freeway-http-jetty` | 1.5.3 | Jetty 12.1.13 |
| `freeway-http-undertow` | 1.5.3 | Undertow 2.4.3.Final |
| `freeway-mq-kafka` | 1.5.3 | Kafka Clients 4.3.1 |
| `freeway-db-hikari` | 1.5.3 | HikariCP 7.1.0 |
| `freeway-benchmark` | 1.5.3 | JMH 1.37, robaho 1.0.29 |
| Tests | — | JUnit 6.1.3, SLF4J 2.0.19 |

---

## 1.5.3 — Core API Convergence Adaptation

### Breaking Changes (Adapting to Core 1.5.3)

- **`FreewayHttpEngine.Wiring` record** — the five positional constructors are replaced by a `Wiring` record + `defaults()` +
  per-field withers (`withSsl`, `withSslParameters`, `withMetrics`), eliminating the hazard of adjacent same-type parameters being
  interchangeable. `ServerHarness`, the only call site, has been adapted.

- **`Orm.FindOptions`** — `findAll(Class, String orderBy, int limit, int offset)` positional sentinels collapse into a
  `FindOptions` record. `""` normalizes to no ORDER BY, and negative values throw immediately. `BenchRepository` has been adapted.

- **`FreewayApp.of(...)` → `FreewayApp.create(...)`** — framework entry points uniformly use `create`, and `of` is reserved for value factories.
  All call sites have been migrated.

- **`SymbolSource.systemProperties()` removed** — the three container-less adapters (Jetty, Undertow, HikariCP)
  now explicitly build the shared chain `SymbolSource.of(coercer, SymbolProvider.systemProperties())`.
  Standalone assembly and the container path now have the same semantics: unknown `${...}` references in `-D` values are now reported correctly.

- **`SslContextFactory` → `SslContexts`** — adapting to core's TLS utility rename.

- **`HttpServerConfig` / `WebServer` constructor convergence** — config is left with only `defaults()` + per-field withers,
  and the four-argument `WebServer` constructor is removed (fixing a TLS test bug that hard-coded `secure = false`). The adapter tests now go through
  `WebServerBuilder` + `Pipelines`, matching the application assembly path.

- **`CloudHttpClientDefault.Wiring` tenth parameter** — the `shutdownGrace` field is filled in, and `RemoteRpcContract`
  passes `null` (taking the built-in default).

- **`ErrorHandler` interface** — core's `ErrorHandlers` is merged into a single `ErrorHandler`; the testkit's
  `Pipelines` has been adapted.

- **`CLAUDE.md` → `AGENTS.md`** — the repository convention file is reorganized following core's convention.

### Kafka Bridge Fixes

- **bridge topic alignment** — the sink writes to the configured bridge topic (rather than the local dispatch topic), and the subscriber
  polls from that same topic. A new `X-Event-Topic` header carries the local topic; cross-JVM events previously could not be delivered.

- **Cross-JVM contract tests** — `CrossJvmEventTest` verifies broker-level
  event delivery with a separate JVM (`CrossJvmRole`), creating a separate bridge topic per run. `KafkaEventSinkIntegrationTest` now uses two containers.

- **`allowed-event-types` empty-list startup warning** — an empty list accepts nothing (indistinguishable from "broker silence"),
  and string-topic events require `java.lang.String` in the list. A warning is emitted at startup.

- **module `final` + hook namespace** — `KafkaModule` is now `final`, and the runtime hook id is changed to
  `"freeway.kafka.lifecycle"` (matching core's `freeway.<module>.<thing>` pattern).

### Benchmark Overhaul

- **`ScenarioSpec` single-point declaration** — scenario definitions scattered across four places converge into one table (method/path/content-type/body/
  echo/JSON/WebSocket flags). Adding a scenario takes only one enum constant + one entry. Fixed two real Undertow echo
  defects: the echo handler performed blocking I/O on an I/O thread (UT000126), and the generic handler read the body in GET scenarios.

- **`BenchFork` split by role** — split from a 291-line single file into `BenchFork` (93 lines), `ForkedRunner`,
  and `BenchProcesses`. `BenchMode` unifies the `--mode` mapping, and an unknown spelling reports an error.

- **`bench jmh` command** — JMH microbenchmark results are written to the same table as HTTP runs, and `list`/`history`/`compare`
  read it uniformly.

- **`BenchRepository`** — the SQL of five commands converges into one repository class, and `BenchRepositoryTest` covers
  ordering, insert-order reads, dispersion, windowed join, and the baseline rule.

- **Engine mislabeling guard** — `bare()` refuses to measure two bare engines in the same JVM. `compare`
  sets exit code 2 when reporting a regression.

- **Unified CLI format** — `BenchFormat` unifies Markdown rendering and number formatting (`rps()`/`micros()`/`delta()`).
  An invalid flag → `UsageException` (exit code 1, no stack trace).

- **Package structure normalized** — `com.jujin.freeway.benchmarks.*` is removed, and dependencies flow in one direction. JMH classes are split into packages by visibility.

- **Event consumption** — `BenchEventListener` consumes `RunStarted`/`ResultCollected`/`RunCompleted`.

- **JMH protocol defaults** — eight benchmark classes declare `@BenchmarkMode(Throughput)`, `@Warmup(5×1s)`,
  `@Measurement(5×1s)`, and `@Fork(2)`, consistent with the protocol document.

### HTTP Engine Adapters

- **TLS uniformly reads `SslSettings`** — Undertow drops its hand-written JSSE construction (`KeyStore`/`KeyManagerFactory`/
  `SSLContext`), so the three engines load TLS material identically.

- **Shared WebSocket base class** — `AbstractWebSocketSession` replaces the request-identification half-layer duplicated in two adapters
  (correlation id, principal, attributes, headers), reducing the WebSocket session by 47%.

- **`JettyWebSocketBridge` promoted to a top-level class** — the engine file shrinks from 568 lines to 427 lines.

- **Engine contract test consolidation** — `freeway-http-adapter-testkit` holds `CompressionContract`,
  `ContextContract`, and `RemoteRpcContract`; ~994 lines of duplicated code become 86 lines.

- **Shared core seams** — `Compression` (gzip semantics), `AbstractWebSocketSession` (request snapshot), and
  `SslSettings` (TLS loading) are shared in three places; adapters keep only engine-specific behavior.

- **Undertow `read-timeout=0`** — `0` now means "no timeout" (mapped to `-1`), and the header budget is aligned with
  the built-in engine's 8192.

- **Jetty `close()` keeps interruption visible** — `InterruptedException` is no longer swallowed.

### HikariCP

- **`invalidate` performs physical destruction** — `HikariDataSource.evictConnection` removes and closes the physical connection, and
  `release` becomes a no-op for invalidated handles. Fixed the issue where connections whose state cannot be recovered were returned to the pool.

- **PoolConfig divergence documented** — HikariCP overrides durations below its floor, ignores `cleanInterval`,
  `longLeased` is always zero, and `close()` does not drain.

- **Tests 13 → 19 cases** — covering primary-vs-plain pool selection and the leak-detection symbol cascade.

---

## 1.5.1 — Configuration Cascade & Cross-Pool Protection

- **Aligned with core 1.5.1** — no adapter API changes (additive release).

- **Configuration cascade** — the two HTTP engines and the Hikari pool accept an injected `SymbolSource`; `freeway.http.*` and
  `freeway.db.pool.leak-detection` now respect the precedence of CLI, JVM properties, environment variables, and files.
  A malformed `freeway.http.websocket.max-frame-size` now fails at startup and names the key.

- **HikariCP cross-pool protection** — releasing a connection that belongs to another pool fails with an actionable `SqlException`.

- **Idiom modernization** — explicit imports, `Thread.ofPlatform` thread factories, named daemon Kafka workers,
  and `Math.floorMod` key buckets.

---

## 1.5.0 — Symbol Specification Migration

- **Aligned with core 1.5.0** — the `freeway-mq-kafka` config types migrate from the removed `commons.config.ConfigSpec`
  to `ioc.symbol.SymbolSpec` (the same `of(key, type, default, parser)` / `key()` / `parse()`
  shape). Core's narrower engine/internal visibility and Default/Impl naming do not affect the adapter API.

---

## Migration Guide

### Upgrading from 1.3.x

1.5.x is a sequence of breaking releases. The main migration points are:

| Old API | New API | Version |
|---------|---------|---------|
| `FreewayApp.of(...)` | `FreewayApp.create(...)` | 1.5.3 |
| `FreewayHttpEngine` multiple constructors | `FreewayHttpEngine.Wiring` record | 1.5.3 |
| `Orm.findAll(Class, orderBy, limit, offset)` | `Orm.findAll(Class, FindOptions)` | 1.5.3 |
| `SymbolSource.systemProperties()` | `SymbolSource.of(coercer, SymbolProvider.systemProperties())` | 1.5.3 |
| `SslContextFactory` | `SslContexts` | 1.5.3 |
| `new HttpServerConfig(host, port, ...)` | `HttpServerConfig.defaults().withPort(...)` | 1.5.3 |
| `new WebServer(engine, config, sink, pipeline)` | `WebServerBuilder.builder()...build()` | 1.5.3 |
| `ConfigSpec` | `SymbolSpec` | 1.5.0 |
| `ModuleEx.subModules()` / `ModuleTree` | `ModuleNode` value type | 1.5.2 |

### Notes

- The Kafka bridge topic must match the subscriber's `freeway.kafka.topics`.
- An empty `freeway.kafka.allowed-event-types` list = reject events of all types (including `java.lang.String`).
- Undertow `read-timeout=0` now means disabled (not "expired").
- `release` after HikariCP `invalidate` is a no-op (not an error).
- An unknown Benchmark `--mode` value reports an error (no longer silently falls back to `keepalive`).

---

## Tests

24 tests green, `spotless:check` clean.

```
mvn test                    # test all modules
mvn spotless:check          # format gate
mvn -pl freeway-http-jetty test   # single module
```

---

## Known Limitations

- Six adapter test classes still hand-write `new WebServer(...)` (engine-specific fixtures: TLS, transport limits, raw WS
  probe) and have not yet migrated to `WebServerBuilder`. This is recorded in the audit.
- The assertions in `KafkaEventSinkIntegrationTest` can be satisfied by same-JVM synchronous local dispatch; a second bus is required for a
  true wire test.
- The Benchmark module is not published to Maven Central (`skipPublishing` + `maven.deploy.skip`).
