# Changelog

## 1.5.2-SNAPSHOT

### Changed

- **jetty: the WebSocket bridge is its own class** — `JettyWebEngine` carried a 110-line nested
  `JettyWebSocketBridge` (upgrade negotiation, frame plumbing, error/close mapping), which made the
  engine file 568 lines and hid the fact that the bridge is a separate collaborator. It is now
  `JettyWebSocketBridge.java`; the engine drops to 427 lines and only starts/stops the transport
  and routes the upgrade. Extraction surfaced a real constraint that is now documented on the
  class: Jetty resolves the listener's lifecycle methods reflectively and fails with
  `IllegalAccessException` when the listener class is not `public`, so the bridge must stay
  `public` — the raw-socket WebSocket probe tests caught exactly that on the first attempt.
- **one copy of every engine contract test** — the two adapters carried near-identical copies of
  three contract suites (`*CompressionTest` and `*RemoteRpcTest` were byte-identical after renaming
  the engine, `*ContextContractTest` differed by a javadoc line break). A new
  `freeway-http-adapter-testkit` module holds them once: `EngineFixture` (config, pipeline, resource
  lookup), `Pipelines`/`HttpClients` helpers, and the `CompressionContract`, `ContextContract` and
  `RemoteRpcContract` suites. Each adapter's test class is now a ~35-line subclass that names its
  engine, so the contract cannot drift between engines and a new adapter gets the whole suite for
  three small classes. ~994 lines of duplicated test code become 86; the suite still runs per
  adapter (jetty 27, undertow 28). The remaining near-copies (`*WebEngineContractTest` 69%,
  `*WebSocketProbeTest` 62%, `*EngineConfigTest` 85%) stay separate: their differences are the
  engine-specific parts (h2c, WS frame limits, adapter-only config keys).
- **the adapters now share three core seams instead of carrying their own copies** — a normalized
  diff of the two HTTP adapters showed three areas that contain no engine API at all: they existed
  because core lacked support for its own public surface. `Compression` (`acceptsGzip` + `gzip`)
  replaces the per-adapter Accept-Encoding/q-value/gzip code and the built-in engine's copy, so all
  three engines decide compression the same way. `AbstractWebSocketSession` replaces the
  request-identification half of both adapter sessions (and of the built-in engine's session):
  correlation id, start time, principal, attributes, method/path/path-variables/query/headers with
  immutable snapshots. `SymbolSource.systemProperties()` replaces the standalone system-property
  source that Jetty, Undertow and HikariCP each declared (22 identical lines ×3). The two adapter
  WebSocket sessions shrink from 232 and 348 lines to 124 and 240, and the shared `closeReason`
  helper now truncates on a UTF-8 code-point boundary — the old byte cut could re-encode to 125
  bytes, over the 123-byte close-frame limit. Engine-specific behaviour is deliberately untouched:
  the transports, per-request dispatch, response mapping, TLS and the documented divergences
  (404 bodies, `readTimeout=0`, header budgets, WS frame limits) stay in each adapter.
- **benchmark: the JMH protocol defaults live in the code, not only in the document** — the
  protocol declares 2 forks, 5×1s warmup, 5×1s measurement and `thrpt`, but no benchmark class
  carried a single strategy annotation, so a plain JMH invocation used JMH's own defaults, and the
  README's example (`-f 0 -wi 5 -i 5`) contradicted the protocol in the other direction. All eight
  benchmark classes now declare `@BenchmarkMode(Mode.Throughput)`, `@OutputTimeUnit(SECONDS)`,
  `@Warmup(5×1s)`, `@Measurement(5×1s)` and `@Fork(2)`, which a plain `org.openjdk.jmh.Main`
  invocation obeys; command-line flags still override them for local iteration, and the README says
  plainly that such a run is reference-only.
- **benchmark harness: assembled by `WebServerBuilder`, so the measured server is the standalone
  one** — `ServerHarness` built its Freeway servers with the raw `WebServer` constructor and a fake
  `event -> {}` sink, which is not the noop sentinel: `WebServer` therefore kept
  `publishEvents = true` and constructed an event object for every request, measured in all three
  Freeway-based engine paths even though nothing observed it. The harness now goes through
  `WebServerBuilder` (the assembly a standalone application uses), which installs the noop sink
  sentinel and appends the default error handler — so the harness no longer passes
  `ErrorHandlers.defaultHandler()` itself, and 413/400 scenarios still return what a real
  application returns. CORS and health remain explicitly disabled, with the reason in the code: the
  scenarios send no `Origin` and probe no health endpoint, so an enabled built-in would add
  per-request work unrelated to what is being compared.
- **benchmark CLI: one table, one number style, one usage-error path** — the five commands each
  formatted their own way (`list` printed bare columns with a `─` rule, `history` and `compare`
  padded their own Markdown, `run` printed raw `%.0f` rates next to `suite`'s `1.20M`), and a bad
  flag surfaced as a stack trace. Now `BenchFormat` owns the formatting: one Markdown renderer with
  numeric columns right-aligned and a bold median row, and `rps()`/`micros()`/`delta()` everywhere,
  so a rate reads `17.3k` in every command. Commands answer to an explicit `Command.name()`
  instead of matching `getSimpleName()` against `"<command>Command"`, so renaming a class can no
  longer silently unhook it, and `CliModule.dispatch` takes just the argument array (the container
  comes from the runtime hook it already captured). A bad flag, an unknown engine/scenario, or a
  non-numeric list is a `UsageException`: one `Error:` line plus a usage hint, exit code 1, no stack
  trace — internal failures keep theirs. `--output` now validates its extension (`run` writes JSON,
  `suite` writes Markdown), which used to be silently ignored: `run --output=report.md` happily wrote
  JSON into a `.md` file. `run` also resolves the engine and scenario before it inserts the run row,
  so a usage error no longer leaves a half-created run behind.
- **benchmark: one package root, and the dependency runs one way** — the module carried two homes
  (`com.jujin.freeway.bench.*` and `com.jujin.freeway.benchmarks.*`) with a cycle between them:
  `BenchFork` imported `bench.cli.BenchRunner` while `BenchRunner` imported
  `benchmarks.ServerHarness`/`client.*`. Now `ServerHarness` is `bench.harness`, `Http11Client` and
  `WsClient` are `bench.client`, `Result` sits in `bench.model` beside the other result types, and
  both `BenchFork` and `BenchRunner` are in `bench.run` — reusing the measurement loop no longer
  means depending on the CLI package. Three JMH classes that only use public API move to
  `bench.jmh`; the five that need package-private engine internals stay in their core packages on
  purpose, each saying so in its javadoc, and the README explains why this module owns no
  `com.jujin.freeway.http` package. `BenchFork`'s subprocess main class is derived from its own
  class now instead of a hardcoded string, and `BenchRunner.stddev` is public because two commands
  use it across the new boundary. `com.jujin.freeway.benchmarks.*`, `bench.cli.BenchRunner` and the
  three old JMH class names are gone — update `exec.mainClass` and any JMH class argument.
- **hikari: `invalidate` destroys, and the HikariCP divergences are stated** — core `1.5.2` adds
  `Pool.invalidate(PooledConnection)`, the pool-level way to say "destroy this connection, do not
  recycle it"; the adapter implements it with `HikariDataSource.evictConnection`, which removes the
  entry and physically closes the connection. Previously the framework could only close the handle,
  and under HikariCP that means rollback + state reset + recycle — a connection whose state could
  not be restored would go back into the pool with `autoCommit` still off. Because HikariCP's proxy
  must not be closed after its entry was evicted (its reset path throws), `release` now recognizes
  an invalidated handle and becomes a no-op, which is what the `Pool` contract promises for the
  invalidate-then-release cleanup order. The class javadoc also records the two `PoolConfig`
  divergences (HikariCP rewrites durations below its own floors and ignores `cleanInterval`), the
  always-zero `longLeased`, and that `close()` does not drain. The adapter tests went from 13 cases
  with tautological assertions to 19 with real ones, and `HikariPoolModule` itself (primary-vs-plain
  pool selection, the leak-detection symbol cascade) now has coverage.
- **kafka: the module is `final` and its runtime hook is namespaced** — `KafkaModule` was the only
  adapter module that was neither `final` nor hook-prefixed: the runtime hook id `"kafka-sink"` is
  now `KafkaModule.LIFECYCLE_HOOK` = `"freeway.kafka.lifecycle"`, matching core's
  `freeway.<module>.<thing>` ids; rename the id in any `before/after` ordering that referenced the
  old string. The javadoc states why its bindings carry no `.id()/.primary()` (`KafkaEventSink` and
  `KafkaSubscriber` have no framework default to outrank, so an application's own binding must fail
  loudly instead of losing silently).
- **aligned with Freeway core `1.5.2-SNAPSHOT`** — `freeway-parent` and the
  `freeway.version` property move together to `1.5.2-SNAPSHOT` (root and all
  five adapter modules, plus the new testkit). The engine RPC tests compile only against the 1.5.2
  API, so the parent and the dependency version cannot drift apart.
- **RPC endpoints are declared, not built per mapping** — core 1.5.2 replaces
  the per-mapping endpoint factory with an `RpcExport` data declaration served
  by a contributed wildcard route, and drops the call bus from the RPC path:
  `RpcEndpoint.route(export, handler, codec)` takes the handler instance and
  stays container-free for standalone assembly. The Jetty and Undertow RPC
  tests therefore feed a `RouteIndex` directly, naming their export and
  dropping the fake container and the bus entirely.
- **no adapter code changes** — the four adapters keep their public surface;
  only the version pins and the two engine RPC tests are touched.
- **module composition is a tree now** — core 1.5.2 deletes
  `ModuleEx.subModules()` and `ModuleTree`: composition is a `ModuleNode` value
  built at the entry point, and a module that only groups others becomes a
  fragment factory. `freeway-benchmark` follows: `BenchDbModule` is a leaf
  (it only contributes `SchemaEntity`), and `BenchApp` composes
  `ModuleNode.app("freeway-benchmark", BenchDbModule.class, DbModule.class,
  CliModule.class)` — modules are named by class, since none of them takes
  constructor arguments.
- **kafka: one home for the keys, and the sink obeys its "must not throw" contract** —
  `KafkaConfig` now declares the twelve `freeway.kafka.*` keys (name, type, default) and resolves
  them in `from(SymbolSource)`, so `KafkaModule` no longer restates a key or a default. The twelve
  `@Value` annotations were dead — the container never calls a static factory — so editing them
  changed nothing and the defaults lived in three places; `suppress-own` is now read strictly, so a
  typo fails naming the key instead of silently disabling own-event suppression. `KafkaEventSink
  .send` swallows a synchronous producer failure and skips a null payload, as the `EventSink`
  contract requires; the DLQ now preserves the record **and then** consults `poison-policy`
  (previously `fail` was unreachable once a DLQ existed, contradicting the README); the wire header
  names live in one `KafkaHeaders` class shared by writer and reader.
- **benchmark: the harness refuses to mislabel an engine, and the regression gate reaches the exit
  code** — `com.sun.net.httpserver.HttpServer` caches its provider once per JVM, so a second bare
  engine in the same invocation would have been measured with the first engine's code and reported
  under the second engine's name; `bare()` now fails naming both engines. `compare` sets exit code
  2 when it reports regressions (contract: 0 = success, 1 = usage error), and a zero-score baseline
  no longer yields ±Infinity. The three Freeway-engine assembly methods collapse into one path, and
  the module has tests for the first time (the provider guard plus a ping smoke test).
- **undertow: `read-timeout=0` means "no deadline", and the header budget matches the built-in
  engine** — the shared contract says `0` disables the timeout, but Undertow reads `0` as "already
  expired" for the request-parse timeout, so a slow or segmented request header was dropped
  mid-request; both socket timeouts now take Undertow's spelling of disabled (`-1`). `MAX_HEADER_SIZE`
  drops from a hardcoded 64 KiB to the built-in engine's 8192, so the same request is no longer
  accepted by one engine and rejected by the other — the comment claiming it "maps the shared
  config" is gone with it. Both are pinned by a raw-socket test that fails on the old mapping.
- **jetty: `close()` keeps the interruption visible** — it swallowed `InterruptedException` (from the
  graceful-shutdown await) without restoring the interrupt flag; Undertow's close already did.
- **undertow: unreachable WebSocket oversize checks removed** — the cap is enforced while Undertow
  fills the buffer (the `getMaxTextBufferSize()`/`getMaxBinaryBufferSize()` overrides), so an
  oversized message fails the read with a 1009 close and never reaches the listener; the manual
  re-checks (plus a javadoc claiming Undertow cannot bound the buffering) could not run. The 1009
  contract stays pinned by `UndertowFrameProbeTest`.
- **benchmark: `Scenario.valueOf(scenario.toUpperCase(Locale.ROOT))`** — under a Turkish locale
  `"ping"` would have uppercased to `"PİNG"` and failed to resolve; the two CLI call sites now spell
  the locale out, matching the rest of the module.
- **benchmark: the reported numbers describe the same server every engine runs** — the scenario
  payloads now come from the client's own `RequestPattern` constants (the bytes the server answers
  with and the bytes the client expects are the same constant, so a mismatch can no longer turn every
  request into a silent "engine 100% error"), the Freeway pipeline carries production's
  `ErrorHandlers.defaultHandler()` so a 413/400 scenario is measured as the response a real
  application returns, and the three engine-assembly methods collapse into one `freewayWith(engine,
  scenario)` path. The `compare` query orders its rows (`ORDER BY id ASC`), so "the last iteration
  wins" no longer depends on the database's return order; a null `commit_sha` no longer NPEs the list
  command; `Http11Client.sendPing()`/the single-arg constructor and the unused `coercer`/`orm` locals
  in two commands are gone.
- **benchmark: one measurement type, one median, no re-query** — the report row
  (`SuiteCommand.SuiteResult`) now holds the shared `BenchRunner.IterationResult` instead of
  restating rps/p50/p95/p99, which also puts `errors` in the suite table and the written report;
  `BenchmarkResult.of(...)` became `forHttpIteration(...)`, filling `unit`/`score_error` inside so no
  call site passes the magic `"req/s"`/`0`; and both commands take the median iteration's id from the
  insert that created it (`orm.insert(...).longKey()`) instead of re-querying the table, sorting and
  updating — the duplicated "stddev + SELECT * + UPDATE" block is gone, and
  `BenchRunner.medianIndex(...)` is the one definition of which iteration represents a run.
- **benchmark: the CLI has tests** — `BenchCliTest` covers the median choice, the row factory, and an
  end-to-end `bench run` (SQLite in-memory) that asserts three persisted iterations, the dispersion on
  exactly one row, and that the marked row is the median iteration (mis-marking it fails the test).
- **benchmark follows the `HttpContextImpl.reset` signature** — core 1.5.2
  narrowed the reset call, so the three HTTP benchmarks drop the position
  argument that no longer exists. Without this the benchmark module does not
  compile against the current core (`mvn clean test` was the real verdict —
  the earlier BUILD SUCCESS came from a stale `target/classes`).
- **spotless** — the two reworked RPC tests are re-flowed to
  google-java-format 1.36.1, the format `verify` enforces.
- **Build**: third-party versions upgraded to latest stable: Undertow
  2.4.3.Final, Jetty 12.1.13, SQLite JDBC 3.53.4.0, H2 2.5.250, SLF4J 2.0.19;
  spotless-maven-plugin 3.10.2, maven-gpg-plugin 3.2.8, benchmark
  exec-maven-plugin 3.6.4. Kafka Clients 4.3.1, HikariCP 7.1.0, JUnit 6.1.3,
  JMH 1.37 and robaho httpserver 1.0.29 were already the latest stable
  releases. The maven-source-plugin and maven-compiler-plugin majors are still
  betas and stay on 3.4.0 / 3.15.0.

## 1.5.1

### Changed

- **aligned with Freeway core `1.5.1`** — `freeway-parent` and the
  `freeway.version` property move to `1.5.1` (root and all five adapter
  modules). No adapter API changes were required: core 1.5.1 is fully
  backward compatible (additive `HttpServerConfig` fields with defaults,
  additive `Http2Connection` overload, docs-only `Extension.asMap` note).
- **adapter knobs resolve through the config cascade** — both HTTP engines
  and the Hikari pool take an injected `SymbolSource`, so `freeway.http.*`
  and `freeway.db.pool.leak-detection` honor CLI, JVM properties, env and
  files instead of JVM properties alone. Direct construction keeps a
  system-properties-only fallback with identical behavior (no test changes).
  Malformed `freeway.http.websocket.max-frame-size` now fails startup
  naming the key instead of silently falling back.
- **HikariCP foreign-release guard** — releasing a connection that belongs
  to another pool fails with an actionable `SqlException` (closing it would
  shut a foreign physical connection); mirrors core `PoolDefault`.
- **modernized idioms** — explicit imports (no wildcards), `Thread.ofPlatform`
  thread factories, named daemon Kafka workers, `Math.floorMod` key buckets,
  collapsed `DatabaseStats` branches.

## 1.5.0

### Changed

- **aligned with Freeway core `1.5.0`** — `freeway-parent` and the
  `freeway.version` property move to `1.5.0` for all adapters.
  `freeway-mq-kafka` config typing migrates from the removed
  `commons.config.ConfigSpec` to `ioc.symbol.SymbolSpec` (same
  `of(key, type, default, parser)` / `key()` / `parse()` shape). Core's
  engine/internal visibility narrowing and the Default/Impl naming
  settlement touch no adapter API — adapters build against the public
  surface only (`CoercerDefault`, `JsonCodecDefault`, `ExchangeMetaDefault`
  keep their 1.4.0 names).

## 1.4.0


### Changed

- **build**: aligned with Freeway core `1.4.0`; typed Kafka config
  resolution migrated from the removed `ConfigValues` to `ConfigSpec`.
- **mq-kafka**: aligned with the core message-domain rename
  `EventBridge` → `EventSink`. `KafkaEventBridge` is now
  `KafkaEventSink` — installed via `EventBus.addEventSink` /
  `removeEventSink`, hook id `kafka-bridge` → `kafka-sink`; inbound
  publishing uses the `EventBusInbound` SPI (`publishInbound(event,
  eventId)` / `publishInbound(topic, payload, eventId)`), which replaces
  the removed `EventBus.publishInboundWithId`. Requires the next core
  release after 1.3.11.

## 1.3.11

### Fixed

- **mq-kafka**: `freeway.kafka.max-retries` / `retry-backoff-ms` /
  `concurrency` went through bare `Integer.parseInt` — a malformed value
  surfaced as a `NumberFormatException` with no context. Parsing now uses
  the core's `ConfigValues` (new in `ioc.symbol`, core ≥ this release's
  dependency baseline) and fails fast naming the key and the rejected raw
  value, matching the `HikariPool` precedent. Regression test pins the
  message shape.

## 1.3.9

### Changed

- **Build**: aligned with the released Freeway core `1.3.9` (was
  `1.3.8-SNAPSHOT`). All adapters build and test against the core release;
  no API breaks — the release is additive on the core side.

### Notes

- **No adaptation needed for the new message-domain APIs** (verified):
  `EventBus.stream()` is a local subscription view and deliberately never
  crosses the MQ bridge; `CallBus` is strictly in-process (its javadoc
  defers remote invocation to freeway-cloud), so there is no Kafka RPC
  bridging to add; the Jetty/Undertow contexts construct the core
  `SseEmitter` directly, so its new reactive pump (`from(Flow.Publisher)`)
  works through both engines without adapter changes.

## 1.3.8-SNAPSHOT

### Changed

- **Build**: aligned with the released Freeway core `1.3.8` (was
  `1.3.8-SNAPSHOT`); Jetty/Undertow
  adapters, WebSocket sessions, contract tests, and the benchmark harness
  aligned with the tightened HTTP SPI and its naming (`HttpContext`,
  `RequestComponents`, `ErrorHandler`, `Http1xParser`).
- **Kafka**: cross-JVM event-bus semantics — outbound envelopes now carry
  `X-Event-Origin` (node identity: `freeway.kafka.client-id`, else a
  per-process UUID) and `X-Event-Channel` (`CLASS`/`TOPIC`); inbound dispatch
  mirrors the channel, so class events re-enter the class channel and topic
  events the topic channel (header-less messages from older producers keep
  topic dispatch). Events are consumed via `EventBus.publishInbound`, so
  inbound traffic is never re-bridged (no queue loop). Own re-broadcast
  events are suppressed by default (`freeway.kafka.suppress-own`, default
  `true`, disable for DLQ replay) to avoid duplicate local delivery in
  publish-and-subscribe-topology nodes.
- **Kafka**: events implementing `EventBus.Keyed` are now published with
  `key()` as the Kafka record key — per-aggregate ordering on the broker and
  key-partitioned parallel consumption (previously every record carried a
  null key, so `freeway.kafka.concurrency` serialized all messages into one
  bucket). Every envelope also carries an `X-Event-Id` UUID for correlation;
  delivery stays at-least-once (documented on `KafkaEventBridge`) —
  consumers needing exactly-once must deduplicate by their own business key.
- **HTTP adapters**: SSL configuration now reads the shared
  `freeway.http.ssl.*` keys of the built-in engine, including the new
  `key-store-type`, `trust-store*`, `client-auth`, `protocols` and `ciphers`
  options (Jetty and Undertow). HTTP/2 over TLS is keyed on
  `freeway.http.ssl.http2` with the core's default of `true` on both
  adapters; the old Jetty-only `freeway.http.http2` remains as the h2c
  (cleartext) toggle and is ignored when TLS is enabled.
- **HTTP adapters**: the shared `HttpServerConfig` knobs are now honored
  where the underlying server supports them — `read-timeout` (Jetty idle
  timeout; Undertow idle + parse timeouts), `backlog`, `receive/send-buffer-size`,
  and `max-connections` (Jetty only, via `NetworkConnectionLimit`).
  Undertow's previous hardcoded 60s/30s idle/parse defaults are replaced by
  the shared 30s default. `write-timeout` and Undertow's `max-connections`
  have no server equivalent and are documented as ignored.
- **HTTP adapters**: gzip response compression implemented with the built-in
  engine's exact semantics (`ResponseFraming` gates, `Vary: Accept-Encoding`,
  compressed `Content-Length`) — enabled by default, min-size 256.
- **Kafka**: `KafkaConfig` now resolves `@Value` on the canonical constructor
  because the refactored IoC `@Value` annotation no longer targets record
  components.
- **DB**: `HikariPool` maps `freeway.db.pool.health-check-timeout` to
  HikariCP's validation timeout.
- **Build**: removed the orphaned `freeway-http-robaho/` directory (stale
  build output from the pre-refactor module that referenced the removed
  `Module2` SPI; it had no sources or POM). The robaho `httpserver` library
  itself stays as a benchmark dependency for the `robaho-native` engine.
- **Docs**: benchmark protocol table updated to the `Http1xParser` naming.
- **CI**: Dependabot refined for Maven and GitHub Actions — weekly Monday
  morning runs, `increase` versioning strategy, and ignore rules for Freeway
  core artifacts, SNAPSHOT versions, and the SLF4J 2.1.0 alpha.

### Fixed

- **Kafka**: consumer close now uses the non-deprecated
  `Consumer.close(CloseOptions)` API with the same 5-second timeout.
- **Undertow**: binary WebSocket frames no longer reference the deprecated
  `org.xnio.Pooled` type (Undertow's `BufferedBinaryMessage#getData()` still
  returns it; the buffers are consumed through local type inference).
- **Kafka tests**: `MockConsumer` construction is marked with a targeted
  deprecation suppression because Kafka 4.3.1 deprecated both public
  constructors without a non-deprecated replacement.
- **Benchmark**: fixed the Javadoc link to `BenchApp` in `CliModule` (the
  reference was unqualified across packages, so Javadoc could not resolve it).

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
