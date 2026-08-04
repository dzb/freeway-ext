# Changelog

## Unreleased (1.3.6-SNAPSHOT)

### Added

- **freeway-benchmark**: JMH-based benchmark module migrated from Freeway core,
  covering HTTP, WebSocket, and DB adapter workloads (`c07c84a`).
- **freeway-http-jetty**: Jetty 12 HTTP engine + WebSocket adapter re-added
  alongside the Undertow adapter.
- **Kafka**: `freeway.kafka.allowed-event-types` allowlist prevents
  deserialization of arbitrary classes; `freeway.kafka.poison-policy` (`skip` |
  `fail`) controls poison-message handling. Unit tests cover topic parsing,
  allowlist resolution, and poison policy parsing.
- **Kafka (breaking)**: the allowlist is empty by default, so typed events are
  now denied until `freeway.kafka.allowed-event-types` is configured; previously
  any class name from the classpath was deserialized.
- **HTTP adapters**: real contract tests for Jetty and Undertow (GET, HEAD
  Content-Length, blocking body echo on a dispatched worker thread).

### Changed

- **Build**: third-party versions centralized in the root POM and upgraded to
  latest stable: Undertow 2.4.2.Final, Jetty 12.1.11, Kafka Clients 4.3.1,
  HikariCP 7.1.0, SQLite JDBC 3.53.2.1, H2 2.4.240, JUnit 6.1.2, SLF4J 2.0.18.
- **Benchmark CLI**: `BenchApp` disables SPI auto-discovery and installs
  `DbModule` explicitly, so the Jetty/Undertow engine modules on the classpath
  no longer collide (duplicate `freeway.db.migration` hook / multiple primary
  `HttpEngine`).
- **BenchRunner**: percentile computation only samples successful requests;
  worker threads replaced with virtual threads; `--mode=ws` requires
  `--scenario=ws_echo`.
- **SuiteCommand**: median lookup queries by `run_id` instead of scanning the
  whole results table; shared `BenchFormat` helper replaces duplicated
  formatting code; unused variables removed.
- **BenchmarkRun**: `gitSha()` result is cached per JVM instead of forking `git`
  for every run.
- **Undertow**: handler execution dispatched from I/O threads to the worker
  pool; worker pool default restored (ioThreads × 8) instead of a single
  thread; graceful shutdown waits in milliseconds instead of truncating to
  whole seconds.
- **Jetty/Undertow**: HEAD responses report the same `Content-Length` as GET
  (RFC 7231 §4.3.2); 204/304 responses omit it.
- **WebSocket adapters**: sends are asynchronous and non-blocking (safe from
  receive/I/O threads); send failures are logged rather than thrown, and
  `close()` initiates the close handshake without waiting.
- **Jetty**: request header snapshots (HTTP + WebSocket sessions) are
  lower-cased for case-insensitive lookup, matching Undertow.
- **Hikari**: `stats()` now reports cumulative borrow wait time and survives
  queries after the pool is closed.

### Fixed

- **Benchmark CLI**: startup crash (`Duplicate contribution id
  freeway.db.migration` caused by double-installed `DbModule`).
- **Benchmark**: percentiles were computed from zero-filled failure slots,
  dropping successful samples.
- **Kafka**: `close()` no longer races `consumer.close()` against the poll
  thread (the consumer is closed from the poll loop only); poison messages are
  logged at error level instead of silently skipped; tombstone records (null
  value) are skipped instead of NPE-ing into the poison path; batch commits are
  bounded by a timeout.
- **Kafka**: allowlist/topics are parsed once per subscriber instead of per
  message; container-level test verifies `@Value` injection of `KafkaConfig`.
- **Jetty**: request handler exceptions now logged at error level instead of
  returning a bare 500; 500 fallback only writes when the response is not yet
  committed; graceful shutdown waits in milliseconds instead of whole seconds.
- **Undertow**: 500 fallback only writes when the response has not started.
- **Benchmark CLI**: command dispatch returns a status code so unknown commands
  exit non-zero *after* the application shuts down cleanly.
- **Benchmark**: timed-out requests are counted as errors; `gitSha()` cache is
  synchronized and now also caches failures (no per-run `git` fork outside a
  repository); history/list show local time; WebSocket close frame is masked.
- **BenchFork**: WebSocket mode selects the `ws_echo` scenario and works
  end-to-end; classpath is derived from the code source so forks work from any
  working directory.
- **Kafka**: `freeway.kafka.client-id` is configurable for producer/consumer;
  unknown `poison-policy` values fail fast; null header values are treated as
  absent.
- **Jetty/Undertow**: WebSocket upgrade 500 fallback respects committed
  responses; 204/304 responses drop a stale `Content-Length`; locale-safe
  header matching in Jetty; echoed `X-Request-Id` is sanitized so a
  correlation id cannot inject response headers.
- **Hikari**: pool construction failures are wrapped in `SqlException`; ignored
  `PoolConfig` fields are documented; tests cover borrow metrics.
- **Compare**: no matching earlier baseline now fails with a clear message
  instead of silently comparing a run against itself.
- **Kafka**: shutdown stops processing an already-polled batch (no publishing
  into a closing EventBus); producer close is bounded to 10s and the bridge is
  closed explicitly by the module; MockConsumer test covers the
  consume/publish/close path.
- **History**: results are fetched via a JOIN over the same time window,
  removing SQLite's per-statement parameter limit; `--days` must be positive.
- **Suite**: `--mode=ws` validates scenarios and engine support up front.
- **Benchmark**: removed dead code (`Result.percentile`,
  `Http11Client` unused fields/overload); imports and fully-qualified names
  cleaned across modules.
- **Build**: added missing XML declaration to the Kafka POM, removed the unused
  `kafka_2.13` test dependency, fixed `ModuleEx {` formatting.
- **Benchmark**: implemented warmup, WebSocket support, score error tracking,
  and regression detection; unified forked and in-process runners (`7253337`).
- **Build**: added SCM override to parent POM (inherited value pointed to the
  wrong repository), fixed test-jar dependency version from
  `${project.version}` to `${freeway.version}` (`c1ef18e`).

## 1.2.1 (2026-06-22)

### Changed

- Adapted to core IoC flush fix (`05c9583`).

## 1.2.0 (2026-06-22)

### Removed

- **freeway-http-robaho**: JDK `HttpServer` + robaho WebSocket adapter.  The adapter's
  abstraction overhead (~20%) came from JDK's `HttpExchange` response serialization,
  per-request object allocations, and eager query-param parsing — all paths that
  `FreewayHttpEngine` (core's built-in raw-socket engine) eliminates entirely.
- **freeway-http-jetty**: Jetty 12 HTTP engine + WebSocket adapter.  Core already
  provides HTTP/2 and WebSocket natively via `FreewayHttpEngine`.

### Changed

- **Undertow**: engine module now binds `HttpEngine` with `primary()`, using the core's
  engine-switching mechanism — the primary binding is what selects the active engine at
  runtime, not a hardcoded default.
- **Undertow**: added `flush()` no-op stub on `UndertowWebSocketSession` —
  Undertow sends frames immediately, no buffering.
- **Undertow**: `statusCode()` renamed to `status()` to match core API.

### Fixed

- **Kafka**: retry on transient producer errors + executor shutdown synchronisation
  (`d114d3f`).
- **Kafka**: poison-pill resilience — recover after fatal errors without broker restart
  (`9dd5cb6`).
- **Undertow**: `onError` protection — guard listener callback against engine-internal
  exceptions (`d114d3f`).
- **WebSocket**: close state machine — single close frame per session, correct handshake
  (`9dd5cb6`, `d114d3f`).
- **WebSocket**: header case normalisation — accept `upgrade`, `Upgrade`, `UPGRADE` etc.
  (`9dd5cb6`).
- **Locale**: `toLowerCase`/`toUpperCase` pinned to `Locale.ROOT` to avoid Turkish `ı`
  bugs (`9dd5cb6`).
- **Core API**: adapted to `shutdownGraceSeconds()` → `shutdownGrace()` returning
  `Duration` (`8abe0a8`).
