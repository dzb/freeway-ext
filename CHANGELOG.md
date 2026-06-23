# Changelog

## Unreleased

### Added

- **freeway-benchmark**: JMH-based benchmark module migrated from Freeway core,
  covering HTTP, WebSocket, and DB adapter workloads (`c07c84a`).

### Changed

- **Undertow**: optimized I/O thread allocation caching request fields, removed
  unnecessary `volatile` access (`5ba7456`).
- **Benchmark**: renamed `BenchmarkRunner` → `ForkedBenchmark` → `BenchFork`,
  removed `BenchmarkMain`, now uses `org.openjdk.jmh.Main` directly
  (`1f57f65`, `52d5964`, `90eb79f`).

### Fixed

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
