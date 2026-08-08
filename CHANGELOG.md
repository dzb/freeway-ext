# Changelog

## 1.3.7-SNAPSHOT

### Changed

- **Build**: upgraded to Freeway core `1.3.7-SNAPSHOT` and adapted to its
  breaking rename of `HttpContext.headerSet(...)` to `setHeader(...)` in the
  Jetty/Undertow adapters, contract tests, and benchmark harness.
- **Build**: third-party versions upgraded to latest stable: Jetty 12.1.12,
  JUnit 6.1.3, spotless-maven-plugin 3.9.0 (google-java-format 1.36.1 kept);
  benchmark build plugins: maven-compiler-plugin 3.15.0,
  exec-maven-plugin 3.6.3, maven-dependency-plugin 3.11.0.

## 1.3.6 (2026-08-07)

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
- **Undertow**: WebSocket receives now enforce
  `freeway.http.websocket.max-frame-size` (default 64 KiB, `0` disables the
  limit); oversized messages are rejected with a 1009 close frame and never
  reach application code. Note: Undertow 2.4 buffers the full message before
  the receive listener runs, so the cap cannot bound the transient buffering
  itself — it bounds message processing and closes the connection.

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

- **Jetty**: SSE now streams events on the open response (`last=false` per
  write) and completes on emitter close; previously every write was sent with
  last-content semantics so only the first write reached the client and later
  events were silently dropped.
- **Jetty**: `freeway.http.websocket.max-frame-size=0` now actually disables
  the message-size limit (previously Jetty's 64 KiB default remained).
- **Jetty/Undertow**: `headerSet` rejects invalid response header names
  (CR/LF/colon/non-ASCII) with `IllegalArgumentException`, closing the
  CR/LF header-name injection path on Undertow and matching core's
  `validateHeaderName`.
- **Tests**: Undertow 1009 oversized-frame probe; Jetty multi-event SSE
  streaming test; response header-name validation tests on both adapters.
- **Kafka**: `close()` now releases the DLQ producer and worker executor even
  when the poll thread outlives the join window (previously leaked non-daemon
  threads hung JVM shutdown); retry backoff is capped at 60 s (an uncapped
  shift overflowed to a negative sleep that livelocked the poll loop); a
  failed DLQ write no longer commits the poison offset — the message is
  redelivered instead of silently lost; poll-loop failures pause briefly
  instead of busy-spinning on the same uncommitted batch.
- **Benchmark**: `bench run` now persists `score_error` (the median row is
  located by DB query instead of an in-memory id that is always 0);
  `--scenario=echo_body` fails fast instead of silently benchmarking GET
  /ping; the WS client counts close frames and payload mismatches as errors;
  the benchmark module's deploy exclusion is corrected (`maven.deploy.skip`
  plus the central-publishing `skipPublishing` property).
- **Undertow**: server options set explicit bounds — 60 s idle, 30 s request
  parse, 64 KiB header budget, and `maxBodySize` propagated to parser-level
  entity/multipart limits (previously Undertow's 2 MiB default silently
  overrode larger configured limits); the parser's `RequestTooBigException` is
  normalized to `BodyTooLargeException` so oversized bodies map to 413 through
  the core exception mapper instead of an unhandled 500; SSE writes are
  non-blocking (a slow SSE client no longer pins a worker thread); WebSocket
  receive errors close the channel; `freeway.http.ssl.key-password` is honored
  (JKS keystores with a separate key password now load).
- **Jetty/Undertow**: WebSocket close reasons are truncated to 123 UTF-8
  bytes (RFC 6455); 205 responses carry no body or Content-Length (matching
  core's 204/205/304 handling).
- **Undertow**: WebSocket send failures are now logged (previously the null
  send callback made failures invisible — the connection silently broke).
- **Kafka**: the producer's `client.id` gets a `-producer` suffix so it is
  distinguishable from the consumer in broker metrics.
- **Tests**: Jetty WebSocket frame probe (handshake, text echo, close,
  oversized-message rejection); 413 payload-too-large boundary tests on
  both adapters; TLS/h2 startup tests on both adapters (self-signed PKCS12
  fixture in test resources).
- **Jetty**: h2 over TLS is fixed — the missing `jetty-alpn-java-server`
  dependency crashed the listener at startup ("No Server ALPNProcessors!")
  and the SSL connector routed to HTTP/1.1 instead of through the ALPN
  factory, so h2 was silently never negotiated. New TLS/h2 tests cover
  HTTPS, h2 via ALPN, and h2c.
- **Build**: CI pins `actions/checkout` and `actions/setup-java` to commit
  SHAs with read-only job permissions.
- **Docs**: README documents the lack of WebSocket send backpressure and that
  `KafkaEventBridge` events carry a null key (keyed fan-out does not apply to
  them).
- **Jetty**: 205 responses now also drop a non-empty body on write (the
  body-allowed guard missed the write-eligibility condition, emitting a
  chunked 205 body; Undertow already dropped it).
- **Undertow**: pooled ByteBuffers are freed after binary and close messages
  (previously leaked one pooled allocation per binary message/close frame);
  the SSE write queue is bounded (a slow client surfaces backpressure as an
  `IOException` from the emitter instead of buffering without bound); the
  oversized-message probe test tolerates close-frame-or-EOF flush ordering.
- **Kafka**: `close()` interrupts the poll thread so it cannot linger in a
  bounded backoff/commit/DLQ send after shutdown, publishing into a closing
  EventBus or hitting the already-closed DLQ producer.
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
- **Kafka**: `freeway.kafka.properties` passes arbitrary client options
  (TLS/SASL etc.) through to producer and consumer.
- **Kafka**: `freeway.kafka.max-retries` / `retry-backoff-ms` add exponential
  backoff retries; `freeway.kafka.dlq-topic` moves poison messages to a
  dead-letter topic (preserving `X-DLQ-Original-Topic` / `-Offset` / `-Reason`
  headers); `freeway.kafka.concurrency` fans processing out by key while
  preserving per-key ordering.
- **HTTP adapters**: Jetty and Undertow serve HTTPS when
  `freeway.http.ssl.enabled` is set (`key-store` / `key-store-password`, JKS or
  PKCS12); Jetty additionally supports HTTP/2 (`freeway.http.http2`: h2 via
  ALPN under TLS, h2c otherwise) and a configurable WebSocket frame limit
  (`freeway.http.websocket.max-frame-size`); Undertow's I/O-thread dispatch can
  be disabled with `freeway.http.undertow.dispatch-io=false`.
- **HTTP adapters**: `ctx.maxBodySize` is enforced from
  `HttpServerConfig.maxBodySize()` on both Jetty and Undertow.
- **Hikari**: `freeway.db.pool.leak-detection` enables Hikari's leak detection.
- **Build**: GitHub Actions CI (`mvn verify -Dgpg.skip=true` on JDK 25) and
  Dependabot keep the build green and dependencies current; Apache-2.0 license
  headers added to all sources; Spotless (google-java-format) enforces a
  consistent style at `verify`.
- **Docs**: `docs/RELEASE.md` documents the snapshot release flow (verify →
  changelog → tag → deploy → benchmark archive).
- **Benchmark**: `compare`/`list`/`history` render local time and tolerate null
  `created_at` rows.
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
