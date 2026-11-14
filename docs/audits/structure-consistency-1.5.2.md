# freeway-ext Structure and Consistency Audit (1.5.2-SNAPSHOT)

- Date: 2026-09-13
- Audit scope: all 5 modules of `freeway-ext` (`freeway-http-jetty` / `freeway-http-undertow` /
  `freeway-mq-kafka` / `freeway-db-hikari` / `freeway-benchmark`), 42 main-source files ≈7,533 lines
- Audit dimensions: **structure** (responsibility partitioning, layering, naming, package organization),
  **consistency** (self-consistency within a module, alignment across modules, alignment with core
  conventions), **simplicity** (duplicate implementations, boilerplate, dead code, unnecessary
  indirection)
- Criteria sources: `freeway-ext/AGENTS.md`, core `AGENTS.md` (the `XDefault`/`XImpl` rule,
  "collaborators are package-private", configuration ownership layering, "prefer small explicit APIs"),
  existing core SPI implementations and the real bytecode of published jars
- Audit baseline: `main`@`375248e`

## 0. Summary of Conclusions

**First, one correction to prior understanding**: this repository **cannot pass a clean build**. The
BUILD SUCCESS previously reported by `mvn test` was a **false green caused by stale incremental
classes**—`freeway-benchmark/target/classes` was stuck at September 6 (the core 1.5.1 era), and after
core moved to 1.5.2-SNAPSHOT the benchmark sources were never recompiled. The real result of
`mvn clean test`: all 92 tests in the 4 adapter modules are green, and the **benchmark module fails to
compile** (see §2; independently reproduced).

Apart from that, functionality and code hygiene are in good shape: no unused types, license headers and
class-level javadoc are 100% consistent, and no TODO is left behind (1 place in benchmark). Structural
problems concentrate in three places that "have already been quietly forking":

| # | Topic | Location | Severity |
|---|-------|----------|----------|
| 0 | **clean build failure**: `Binder.install` was removed in core 1.5.2, `HttpContextImpl.reset` dropped from 11 parameters to 10, and benchmark did not follow | benchmark ↔ core | **blocking** |
| 1 | The two HTTP adapters are near-clones: ≈200 lines of production code repeated byte for byte, ≈450 lines of test code nearly copied | jetty ↔ undertow | high |
| 2 | Pooled context does not clear exchange metadata: principal/attributes/correlationId/startTime leak across requests | jetty + undertow | high |
| 3 | `ssl.enabled` semantics fork from core: when only the keystore is configured it silently starts plaintext, while the framework claims HTTPS | jetty + undertow | high |
| 4 | `isSecure()`/`sslSession()`/`remoteAddress()` are not implemented: under TLS `isSecure()==false`, and the access-log IP is always `-` | jetty + undertow | high |
| 5 | The 12 `@Value`s in `KafkaConfig` are dead annotations, and the defaults are declared three times across three files | kafka | high |

## 1. Audit Method

1. **Read every module closely**: all main sources of the 5 modules were read (benchmark 28/28 files;
   JMH classes read line by line rather than sampled).
2. **Normalized cross-module comparison**: `Jetty*` and `Undertow*` classes with the same name were
   diffed line by line after normalizing engine names, quantifying duplication; blocks claimed to be
   "byte-for-byte identical" were compared a second time after de-indenting.
3. **Compare against core**: same-named SPI implementations were compared method by method; verified
   whether core already has a reusable utility, so that "duplication" is not misjudged as "must
   duplicate".
4. **Conclude only after tracing to the implementation path**: judgments about "whether a piece of code
   takes effect" (whether `@Value` is read by the container, whether `Binder.install` exists, whether
   `resetExchangeMeta` is called, whether gzip detection has drifted) were all traced to core's assembly
   path or the **bytecode of the published jar**—never inferred.
5. **Build verification**: `mvn -o clean test` (full, non-incremental), `mvn -o spotless:check`.

## 2. P0: Clean Build Failure (Independently Reproduced)

### 2.1 Facts

```
[INFO] Freeway :: HTTP :: Jetty ....... SUCCESS  (22 tests)
[INFO] Freeway :: HTTP :: Undertow .... SUCCESS  (21 tests)
[INFO] Freeway :: MQ :: Kafka ......... SUCCESS  (37 tests, 4 skipped)
[INFO] Freeway :: DB :: HikariCP ...... SUCCESS  (12 tests)
[INFO] Freeway :: Benchmark ........... FAILURE
[ERROR] COMPILATION ERROR :
[ERROR] freeway-benchmark/.../bench/db/BenchDbModule.java:[35,11] cannot find symbol
[ERROR] freeway-benchmark/.../http/engine/FilterChainBenchmark.java:[74,14] method reset in HttpContextImpl cannot be applied to given types
[ERROR] ... 9 reset calls fail in total (FilterChain 3, ContextLookup 1, ContextOutput 5)
```

### 2.2 Two Root Causes (both checked against the bytecode of the core 1.5.1 / 1.5.2-SNAPSHOT jars)

| Symbol | core 1.5.1 | core 1.5.2-SNAPSHOT | benchmark status |
|---|---|---|---|
| `Binder.install(ModuleEx)` | exists | **removed** (the interface has only `bind`/`contribute`) | still called at `BenchDbModule.java:35` |
| `HttpContextImpl.reset(...)` | 11 params (the last ones being `boolean http10, boolean keepAlive`) | **10 params** (`http10` removed) | 9 places still pass 11 arguments |

The removal of `Binder.install` is directly visible in the core source (`freeway-ioc/.../Binder.java` has
only two methods), and the `reset` signature change comes from core commit `1cc01a3`.

### 2.3 Why It Was Not Exposed Earlier

`freeway-benchmark/target/classes` has a timestamp frozen at September 6, earlier than the version-bump
commit `346b7c9`. maven-compiler-plugin's incremental decision considered the sources unchanged and
skipped recompilation, so the **build passed using classes compiled in the 1.5.1 era**. Any `clean`, or
CI (a fresh checkout), fails immediately—which is also why the "green locally, red on CI" discrepancy can
be so subtle.

### 2.4 Fix Recommendations

1. `BenchDbModule.java:35`: switch to the 1.5.2 module installation form (`ModuleEx` composition /
   `Binder`'s existing API), or confirm whether `install` has a replacement entry point.
2. The 9 `reset(...)` calls: delete the extra trailing `true` (the removed `http10`), and converge the
   calls into one local fixture method (e.g. `ContextFixture.reset(ctx, method, path, ...)`), so a future
   core signature change touches one place.
3. While there, fix 2.1/3.9 in §4.5: those 3 benchmarks that "write reset into the measured body" can be
   changed to `@Setup` presetting.
4. **Process recommendation**: CI already uses `mvn verify`, but confirm it runs clean; `mvn test` cannot
   be evidence that "it builds".

## 3. Cross-Module Findings

### 3.1 Structure

**3.1.1 Benchmark's package organization uses three naming schemes, and 4 packages overlap with core (medium)**

`freeway-benchmark` simultaneously uses `com.jujin.freeway.bench.*`, `com.jujin.freeway.benchmarks.*` and
`com.jujin.freeway.http.*`. The four packages of the third scheme **already exist** in the core
`freeway-http` jar (`http.engine` 85 entries, `http.engine.ws` 9, `http.route` 10, `http.body` 9),
forming a cross-artifact split package: illegal under JPMS, and when reading, the package name cannot tell
you which artifact a class belongs to. Of the 8 JMH classes, only 5 **must** stay in the core packages
(they depend on package-private `HttpContextImpl.reset`, `Http1xParser`, `WebSocketFrame.read/write`);
`JsonCodecBenchmark`/`RouteIndexBenchmark`/`MultipartFormBenchmark` use only public APIs and can move to
`com.jujin.freeway.benchmark.jmh.*` immediately. The compile failure in §2 is the direct cost of this
white-box coupling.

In addition, `bench.cli` ↔ `benchmarks` is a **two-way dependency** (`BenchFork` → `bench.cli.BenchRunner`,
`BenchRunner` → `benchmarks.ServerHarness/client.*`), which shows that these are not layers but two halves
left over from one migration.

> **(landed 2026-09-13)** The whole `benchmarks.*` package was merged into `bench.*`: `ServerHarness` →
> `bench.harness`, `Http11Client`/`WsClient` → `bench.client`, `Result` → `bench.model` (alongside
> `BenchmarkRun`/`BenchmarkResult` in the result model), `BenchFork` → `bench.run`, and `BenchRunner` was
> moved from `bench.cli` to `bench.run`—dependencies thus become one-way
> `cli → run → harness/client → model`, and the CLI is no longer the mandatory path to "reuse measurement
> logic". The three JMH classes that use only public APIs moved to `bench.jmh` (checked one by one:
> `RouteIndex` is a public class + public constructor + public `RouteMatch`, `MultipartForm.parse` public,
> `JsonCodecDefault` public); the other 5 stay in the core packages, and each class's javadoc states the
> package-private dependencies that force it to stay (`HttpContextImpl.reset`, `Http1xParser`/`ParsedRequest`,
> `WebSocketFrame.read/write`); the JMH list in the README now also says "this module does not own any
> `com.jujin.freeway.http` package". `BenchFork.MAIN_CLASS` changed from a hard-coded string to
> `BenchFork.class.getName()`, so it cannot mismatch after a package change. The migration also exposed one
> out-of-package access: `BenchRunner.stddev` was package-private and used by two CLI commands, and is now
> public. Verification was not just compilation: both kinds of JMH were actually run (the migrated
> `bench.jmh.JsonCodecBenchmark` and the white-box `http.engine.Http1xParser`/`HttpContextOutput`), and
> `BenchFork` really forked one round (500 requests ok=500 errors=0).

**3.1.2 Classes over 500 lines**

`ServerHarness` (569), `JettyWebEngine` (553), `KafkaSubscriber` (520). Their common shape is "assembly +
per-request path" in one class; per-class judgments are in §4, where `JettyWebEngine` and `ServerHarness`
have clear low-cost ways to fix their responsibility overflow.

### 3.2 Consistency

**3.2.1 The two HTTP adapters behave inconsistently on the same contract (medium)**

| Scenario | Jetty | Undertow |
|---|---|---|
| WS upgrade with no matching route | hand-written 404 + `text/plain` + `"Not Found"` (`JettyWebEngine.java:315-320`) | `ResponseCodeHandler.HANDLE_404` (`UndertowWebEngine.java:342-345`), body/type decided by the library |
| WS upgrade rejected | hand-written 400 + `"WebSocket upgrade rejected"` (`:334-338`) | delegated to `Handlers.websocket(...)` (`:388-389`) |
| WS endpoint `open()` throws | `LOG.warn` + 500 + dedicated message (`:339-347`) | rethrown → `LOG.error` + generic 500 body (`:320-331`, `:379-386`) |
| Error-body constants | 4 `byte[]` (`:61-67`) | 1 `String` (`:73`) |
| `X-Request-Id` | string literal in two places (`:163-164`) | defines `X_REQUEST_ID` (`:72`), but request reads still use a literal (`:337`) |
| Request header limit | default 8 KiB, consistent with core ✅ | hard-coded 64 KiB (`:173`), 8× core, while the comment claims it "maps the shared configuration" |
| WS message limit default | 64 KiB (`:147`) | 64 KiB (`:144`), while the built-in core engine hard-codes 16 MiB |
| `readTimeout=0` | no timeout set, consistent with core's "0 disables" ✅ | mapped to `REQUEST_PARSE_TIMEOUT=0`; Undertow short-circuits only on `-1` (`ParseTimeoutUpdater.handleSchedule` bytecode), while 0 sets `expireTime=now`, so slow/fragmented request headers get disconnected ⚠ |
| WS frame limit per startup | local variable inside `start()` | engine field `private volatile long wsMaxMessageSize` (`:79/143/346`), residual across a second start |

README already documents some differences (max-frame-size rejection timing, `onError`/`onClose`), but **not
the ones in the table above**.

**3.2.2 Module class naming and registration style are not uniform (medium-low)**

| Module | Class name | Modifier | Binding |
|---|---|---|---|
| jetty | `JettyWebEngineModule` | `public final` | `.id("jetty").primary()` |
| undertow | `UndertowWebEngineModule` | `public final` | `.id("undertow").primary()` |
| hikari | `HikariPoolModule` | `public final` | `.id("hikari").primary()` |
| kafka | `KafkaModule` | `public` (not final) | no `id()`, no `primary()` |

`KafkaModule`'s RuntimeHook id `"kafka-sink"` also lacks core's conventional `freeway.` prefix (core uses
`freeway.http.server`, `freeway.db.migration`). The module POMs' `<description>` values come in four styles.

**3.2.3 Contract-test granularity has forked (medium)**

| Contract | jetty | undertow |
|---|---|---|
| GET / HEAD | split into two cases | merged into one |
| dispatch to worker | none | `echoBodyWorksWhenDispatchedToWorker` (Undertow-specific, reasonable) |
| the other 5 items | present | present |

`JettyTlsHttp2Test` (210 lines) and `UndertowTlsTest` (151 lines) differ by 83 lines, with asymmetric
coverage; test class naming has also forked (`JettyWebEngineContractTest` ↔ `UndertowHttpContractTest`,
`JettyWebSocketProbeTest` ↔ `UndertowFrameProbeTest`, `JettyTlsHttp2Test` ↔ `UndertowTlsTest`).

**3.2.4 Documentation and implementation have drifted (medium, cross-module)**

- `README.md:69-71` says the key emitted by `KafkaEventSink` is always null; in fact `Keyed` events use
  `key()` (`KafkaEventSink.java:121`, the change is recorded in CHANGELOG).
- core `docs/application.properties.sample:395-397` says `freeway.kafka.topics` determines the outbound
  topic; in fact `KafkaEventSink` holds no config, and the bus derives the outbound topic from the event
  type.
- 6 places on the benchmark side (engine capability table, engine count, extension list, JdbcUrl
  ownership), see §4.5.

### 3.3 Simplicity

**3.3.1 Duplication between the two HTTP adapters: ≈200 lines of production code (high)**

Blocks that are **byte-identical after de-indenting**, once engine names are normalized:

| Duplicate block | jetty | undertow | Lines |
|---|---|---|---|
| `systemProperties()` | `JettyWebEngine.java:95-116` | `UndertowWebEngine.java:102-123` | 22 |
| `splitCommaSeparated()` | `:284-290` | `:309-314` | 7 |
| `parseMaxFrameSize()` (including the error message) | `:295-303` | `:299-307` | 9 |
| `safeCorrelationId()` (including javadoc) | `:370-377` | `:409-416` | 8 |
| `snapshotPathVariables()` | `:384-386` | `:423-425` | 3 |
| `qValueIsZero()` | `JettyHttpContext.java:292-304` | `UndertowHttpContext.java:388-400` | 13 |
| `gzip(byte[])` | `:310-316` | `:407-413` | 7 |
| WebSocket accessors, whole block | `JettyWebSocketSession.java:78-149` | `UndertowWebSocketSession.java:190-261` | 72 |

The "fully byte-identical" part alone is **141 lines × 2 copies**. Near-duplicates also include
`acceptsGzip()` (18 lines, only the header-fetch expression differs), `compressibleContentType()`,
`output(byte[])` (35 lines each, differing only in the final write call), and `closeReason()` (9 of 10
lines identical). Whole-class duplication rates computed by a subagent with difflib: `HttpContext` 215/425,
`WebSocketSession` 170/368, `WebEngine` 140/482.

**3.3.2 `systemProperties()` is duplicated across three modules (high)**

The same 22-line anonymous `SymbolSource` is **byte-identical in jetty / undertow / hikari**
(`JettyWebEngine.java:95-116`, `UndertowWebEngine.java:102-123`, `HikariPool.java:95-116`), including the
javadoc and the `UnknownSymbolException` branch. `SymbolSource` is owned by core, so this should be a
factory provided by core (`SymbolSource.systemProperties()`).

**3.3.3 Duplicate blocks can move down into core without violating the leaf-node constraint**

`AGENTS.md` states that "the four adapters are leaf nodes that do not depend on each other", and the README
stresses that the two engine modules are independent. That is a different matter from "source duplication":
the blocks above are all **pure functions** (gzip, `Accept-Encoding` parsing, correlation-ID sanitizing,
snapshots, attribute parsing) with no engine coupling; and the two adapters **already both depend on core's
`freeway-http`**. Moving them down into core:

- introduces no adapter → adapter dependency; the leaf-node rule remains intact;
- puts them on the same layer as core's existing `MediaTypes`, `ResponseFraming`, `HttpUtils`, consistent
  with core `AGENTS.md`'s configuration ownership layering and "small explicit APIs";
- the cost is one core change + a version bump (core 1.5.2-SNAPSHOT is in development; good timing).
- counter-evidence: these copies **have already forked**—`X-Request-Id` constant usage, error-body type, how
  the WS frame limit is held, and `ssl.enabled` semantics are all instances of "should be consistent but is
  not".

**3.3.4 The three copies of gzip negotiation logic have drifted (medium-high)**

core `HttpContextImpl.java:401-425`, `JettyHttpContext`, and `UndertowHttpContext` each have
`acceptsGzip/qValueIsZero`, but the core version **iterates over all values of the header** and
**lower-cases the q parameter first**, while the two adapters take only the first value and treat `"q"`
case-sensitively. So with inputs such as `Accept-Encoding: br` on one line and `gzip;q=0` on another, or
`Q=0`, the adapters and the built-in engine reach different conclusions—while both comments say "same
semantics as the built-in engine".

**3.3.5 Test duplication ≈450 lines (medium-high)**

| Test pair | Lines | Differing lines after normalization |
|---|---|---|
| `JettyCompressionTest` ↔ `UndertowCompressionTest` | 169 / 169 | **0 (complete copy)** |
| `JettyRemoteRpcTest` ↔ `UndertowRemoteRpcTest` | 156 / 156 | 4 |
| `JettyEngineConfigTest` ↔ `UndertowEngineConfigTest` | 103 / 119 | 20 |
| `JettyWebEngineContractTest` ↔ `UndertowHttpContractTest` | 262 / 274 | 158 |
| `JettyWebSocketProbeTest` ↔ `UndertowFrameProbeTest` | 305 / 299 | 228 (engine differences are large, reasonable) |
| `JettyTlsHttp2Test` ↔ `UndertowTlsTest` | 210 / 151 | 83 (coverage asymmetric) |

core already publishes a test-jar (`freeway-http-1.5.2-SNAPSHOT-tests.jar`, containing helpers such as
`StubHttpContext`); writing "engine-independent contracts" as abstract base classes in the core test-jar
and having the two adapters extend them is a ready-made, workable path.

> **Historical snapshot (1.5.2)**: this artifact has not been produced since 1.5.6 (core `b3706a3c`
> removed the `tests` classifier of `freeway-http`, and ext `621d8d7` removed the dependency at the same time).
> Today shared engine contracts go through `freeway-http-adapter-testkit` (an ordinary `src/main`
> artifact). This paragraph is kept as the judgment record of that time, not as the current path.

## 4. Detailed Review by Module

> Verification markers: ✅ = I independently verified (read source/bytecode/ran the build); ⚠ = from the
> module deep review, not independently verified by me.

### 4.1 freeway-http-jetty

**Structure**
- `JettyWebEngine` at 553 lines carries assembly + TLS/ALPN stack + dispatch + WS bridge; among that,
  `JettyWebSocketBridge` (`:444-552`, 111 lines, `public static final`) is a complete independent
  transport bridge, yet it is nested inside the engine and **used in only one place in the whole
  repository, `:324`**; its `public` visibility has no justification (core `AGENTS.md`: "a type is public
  only when another package must assemble or substitute it"). Lifting it to a top-level package-private
  class would bring the engine down to ≈440 lines and align the file split with Undertow. ⚠ (whether the
  visibility is required by Jetty's reflective lookup was not verified on a real machine; after the change
  the WS cases must be run)
- Dead code: `JettyHttpContext.responded()` (`:205-207`) and `isResponded()` are duplicate accessors for
  the same state; `JettyWebSocketSession.completionCallback()` (`:229-231`) is a meaningless wrapper that
  "returns a constant". ⚠

**Consistency**
- ✅ [High] `JettyHttpContext.reset()` (`:69-79`) does not call core's `resetExchangeMeta()`, the same
  defect as `UndertowHttpContext.reset()` (`:73-85`). Core's contract is explicit:
  `AbstractHttpContext.java:68-73` + `ExchangeMetaDefault.reset()` clear principal/attributes, roll a new
  correlationId, and refresh startTime; `ExchangeMetaDefault.setCorrelationId` has **no effect** on
  null/blank (`:56-61`). Consequences: a `ThreadLocal`-pooled context reused across connections on a
  thread lets the next request read the previous request's principal/attributes, `startTime` freezes
  (affecting latency statistics), and a request without `X-Request-Id` inherits the previous request's id.
- ✅ [High] `JettyWebEngine.java:215` uses `Boolean.parseBoolean(resolve(SSL_ENABLED, "false"))`,
  discarding core's three-state semantics (`HttpModuleConfig.activated`: explicit true/false wins; if
  unset, look at whether the keystore exists; an invalid value fails with an error naming the key). When
  only `ssl.key-store` is configured: core's `WebServer.secure()` judges HTTPS (the javadoc at
  `WebServer.java:184-192` says so explicitly), yet the adapter starts plaintext without a warning.
- ✅ [High] `isSecure()`/`sslSession()`/`remoteAddress()` are not implemented (grep finds 0 hits in both
  adapters). `HttpRequest` defines the three as "transport bridges override it" (defaults false/null/""),
  and core's built-in engine implements them (`HttpContextImpl.java:100-116`). Consequences: under TLS
  `isSecure()==false`, `sslSession()==null` (Secure Cookie, redirects, and mTLS decisions take the wrong
  branch), and the client IP logged by `AccessLogFilter` is always `-` (`AccessLogFilter.java:37-38`). On
  the Jetty side the implementation cost is very low (`Request.isSecure()`, `Request.getRemoteAddr()`,
  `SslSessionData.ATTRIBUTE`).
- [Medium] `JettyHandle.close()` (`:427-440`) swallows `InterruptedException` too and does not restore the
  interrupt flag, while the Undertow side restores it correctly (`UndertowWebEngine.java:463-465`).
- [Medium] The comment at `:138-143` says `maxConnections` "rejects excess connections at accept time",
  but in fact `NetworkConnectionLimit` is `setAccepting(false)` (pause accepting, leave connections in the
  OS backlog), whereas core's built-in engine closes the socket immediately. ⚠
- [Low] `setStatus` lacks core's 100–599 validation; `sse()` lacks core's "throw if already committed"
  guard; `writeTimeout`/`h2.reset-*` are silently ignored with no comment (Undertow at least wrote a
  comment).
- [Medium] Keys such as `systemProperties/max-frame-size/http2` appear as bare literals and no constants
  are introduced.

**Simplicity**
- [Medium] `JettyWebEngine.snapshotQueryParameters` (`:388-395`) and `JettyHttpContext.parseQueryParams`
  (`:318-325`) are **identical word for word**; `method()/path()` are likewise implemented twice.
- [Low] `JettyCompressionTest`/`JettyRemoteRpcTest` are almost word-for-word identical to the Undertow
  versions (see §3.3.5).

### 4.2 freeway-http-undertow

**Structure**
- [Medium] `UndertowHttpContext` embeds an 86-line SSE writer (`:186-276`, including
  queue/backpressure/`MAX_QUEUED_WRITES`), while Jetty's counterpart is a 35-line anonymous class
  (`JettyHttpContext.java:141-175`). Recommend lifting it to `UndertowSseOutputStream`.
- [Medium] `wsMaxMessageSize` is an engine mutable field carrying the configuration of a single start
  (`:79/143/346`), while Jetty uses a local variable; tests can only read the field reflectively
  (`UndertowEngineConfigTest.java:90-94`).
- ✅ The public API surface is more contained than Jetty's (both context/session classes are
  package-private and do not leak nested bridge types).

**Consistency**
- ✅ [High] The same missing `resetExchangeMeta()` as jetty (`:73-85`).
- ⚠ [High] `readTimeout=0`: the documented core semantics are "disabled" (`HttpConfigKeys.java:83-84`),
  but the adapter writes it into both `IDLE_TIMEOUT` and `REQUEST_PARSE_TIMEOUT` (`:170-172`). I verified
  the bytecode of `ParseTimeoutUpdater.handleSchedule(long)`: only `-1` short-circuits
  (`expireTime = -1`); `0` follows the normal path and sets `expireTime = now`, i.e. it is judged "expired
  immediately". The trigger is request headers arriving across TCP segments (large Cookies, slow links,
  fragmented sends).
- ✅ [Medium] `MAX_HEADER_SIZE = 64 * 1024` (`:173`) is 8× core's built-in engine (8192 at
  `Http1xParser.java:22`), while the comment describes it as "mapping the shared Freeway configuration".
  `SSL_ENABLED` semantics are the same as jetty's ✅ [High].
- ⚠ [Medium] `UndertowWebSocketSession`'s oversize checks (`:112-117`, `:138-140`) and `rejectOversized`
  (`:354-367`) are dead code: Undertow's `BufferedTextMessage.checkMaxSize()` sends 1009 and throws
  `IOException` during buffering, so `onFullTextMessage` never receives an oversize message; the comment
  at `:354-358` says the opposite of the library's behavior.
- ⚠ [Medium] The self-written TLS (`:232-285`) forks from core's `SslContextFactory`: it lacks
  `key-alias` (Jetty has it) and SNI/reload (core has them), and a truststore path without a password does
  not fail fast (core throws).
- ⚠ [Medium] The IOException rewrapping in `handle()`/`dispatch()` (`:334-359`) is an indirection layer
  with no benefit.
- ⚠ [Low] `headers(String)` returns a mutable `ArrayList` (Jetty returns `List.copyOf`); `responded` is
  not volatile (Jetty's is); `close()` bypasses `sendLock`; `sse()` does not check whether the response is
  committed; `setIoThreads(availableProcessors())` differs from the library default `max(cpu,2)`, and sits
  right next to an obsolete comment describing "the removed worker pool" (`:164`, `:185-186`).

### 4.3 freeway-mq-kafka

**Structure**
- ⚠ [Medium] `KafkaSubscriber` at 520 lines carries 7 responsibilities (client construction 116-144,
  lifecycle 146-152/448-519, poll/commit 154-191, per-key bucketed concurrency 193-251, retry/backoff
  253-306, poison/DLQ 308-363, wire-format parsing 365-446); `running` simultaneously means three things:
  "loop alive / commits allowed / stop requested". Recommend splitting into
  `KafkaHeaders`/`KafkaDlq`/`KafkaRecordProcessor`/`KafkaClients`.

**Consistency**
- ✅ [High] The 12 `@Value`s in `KafkaConfig.of` are **dead annotations**, and the defaults are declared
  three times: 9 bare `resolve(key, default)` calls at `KafkaModule.java:44-57`, 12
  `@Value("${key:default}")` at `KafkaConfig.java:60-71`, and 3 `SymbolSpec`s at
  `KafkaModule.java:31-36`. I traced core's assembly path to confirm: `ContainerImpl.constructInstance`
  only goes through `BeanIntrospector.selectConstructor` (`ContainerImpl.java:485-489`), and `.to(Class)`
  at `BindingImpl.java:99` lands in the same place—**the static factory `of(...)` is never on the
  container assembly path**, and `KafkaModule` explicitly calls it with a provider lambda. Therefore
  changing the defaults in `@Value` **has no effect whatsoever**. The class javadoc
  (`KafkaConfig.java:29-30`) "resolved from the config cascade via @Value" is the opposite of the fact.
  Recommend refactoring after core `HttpModuleConfig` ("Key declarations: name, type and default stated
  exactly once" + `from(SymbolSource)`) and deleting `@Value`.
- ✅ [Medium] `producer.send(...)` at `KafkaEventSink.java:131-135` is not wrapped in try/catch, violating
  core `EventSink`'s explicit contract ("**send must not throw**", `EventSink.java:13-17`);
  `KafkaProducer.send` can synchronously throw
  `TimeoutException`/`IllegalStateException`/`SerializationException`. Yet the serialization-failure
  branch in the same method (`:113-118`) is handled—a self-contradictory stance.
- ✅ [Medium] DLQ and poison-policy behavior/documentation contradict each other: `handlePoison`
  (`:309-318`) **unconditionally `return true`** after DLQ success, so the `failOnPoison()` branch at
  `:332` is unreachable when a DLQ exists; while `README.md:73-75` explicitly says "With a DLQ topic, they
  are moved to the DLQ first and the policy then decides whether processing continues (`skip`) or stops
  (`fail`)". Tests cover only skip+DLQ.
- ⚠ [Medium] `suppress-own` uses `Boolean.parseBoolean` (`KafkaModule.java:57`): a typo silently becomes
  false = silently disables own-event suppression; this is inconsistent with the same module's fail-fast
  stance on max-retries/poison-policy and with core `SymbolSpec.activated`'s strict criterion.
- ⚠ [Medium] Wire header literals are duplicated across files: the write side `KafkaEventSink.java:125-130`
  and the read side `KafkaSubscriber.java:383/394/402/408` (the 3 DLQ headers `:354/357/361`) each hold
  the same batch of strings, and the write side uses `channel.name()` while the read side hard-codes
  `"CLASS"` (`:397`). The values agree today (not an existing bug), but a rename on either side silently
  misroutes.
- ⚠ [Low] `Defer.within` is an empty scope (`:366-389`, containing only one `publishInbound`), pure
  overhead of one `ScopedValue` binding + `DeferScope` allocation per message.
- [Low] Two identical per-record processing loops inside `KafkaSubscriber` (`:197-210`, `:224-241`) can be
  extracted into `drain(...)`; `close()` repeats its teardown; the `Callable` exists only to `return null`.
- ⚠ [Low] `event.getClass()` at `KafkaEventSink.java:107` has no null guard, while core allows a topic
  payload to be null (`EventBus.java:179`); boot events are silently filtered by package-name prefix, and
  core has no corresponding mechanism.

### 4.4 freeway-db-hikari

The module is the smallest (191 + 40 lines), and overall quality is high: responsibility partitioning is
isomorphic to core's `PoolDefault`, the public API surface is minimal (all vendor types are private), no
RuntimeHook is needed (the container closes `AutoCloseable` uniformly), `DatabaseStats`'s 8 arguments are
not misordered, `close()` is idempotent, and repeated release is safe. `longLeased` being always 0 (`:175`)
is an **undocumented semantic fork** (core computes it for real).

**One correction to a deep-review conclusion**: the module deep review rated as high risk that "`HkConn`
exposes Hikari's proxy connection, and `connection().close()` returns it rather than destroying it, so
core's three safety paths for 'destroying a polluted connection' become ineffective
(`BatchQueryImpl.java:191`, `DatabaseImpl.java:220`)". After checking the bytecode of HikariCP 7.1.0, I
believe **the actual impact was overestimated**: before recycling, `ProxyConnection.close()` executes
`if (isCommitStateDirty && !isAutoCommit) delegate.rollback()` and
`poolEntry.resetConnectionState(this, dirtyBits)` (resetting autoCommit/isolation/readOnly/catalog); only a
failure of the reset itself takes the eviction path; `HikariDataSource.evictConnection(Connection)` does
exist. So the causal chain "a polluted connection flows back and is picked up by the next borrower" does
not hold. **What does hold is the semantic difference**: core's "physical destroy" intent becomes "rollback
+ state reset + recycle" under Hikari. Recommend not changing the adapter logic, but: ① add
`Pool.invalidate(PooledConnection)` to core so "destroy" can be expressed (on the Hikari side use
`evictConnection`); ② until then, record the difference in the class javadoc.

The other medium items (all ⚠, not independently verified): PoolConfig durations are silently rewritten by
Hikari (`maxLifetime<30s→30min` etc., while core `PoolDefault` enforces them strictly); `close()` does no
drain/wait (core is a four-phase waiting close); tests all use the 1-arg constructor, `HikariPoolModule`
and the cascade path have zero coverage, malformed-value branches have no coverage, and there are
always-true assertions (`idle()>=0`, `maxSize()<=3`) and swallowed exceptions. Low items: the provider
lambda can degenerate to `.to(HikariPool.class)`; the `hikariConfig` field can be deleted (`HikariDataSource
extends HikariConfig`); the two public constructors lack `Objects.requireNonNull`; wildcard imports in
tests violate the explicit-imports convention declared in CHANGELOG.

> **(landed 2026-09-13)** For ①, ② and this section's medium/low items, see this round's update note at the
> end of §5; the only item not adopted is "the provider lambda degenerating to `.to(HikariPool.class)`"—
> `HikariPool` has two public constructors, and only an explicit lambda can make clear that the container
> path must go through the `SymbolSource` cascade.

### 4.5 freeway-benchmark

**P0 see §2.**

**Structure**
- ⚠ [Medium] Package organization is a migration leftover (`bench` and `benchmarks` both originate from the
  core migration commit `c07c84a`, and the two depend on each other both ways); `BenchRunner` sits in
  `cli`, making "reuse measurement logic" necessarily depend on the CLI package.
- ⚠ [High] `ServerHarness` (569 lines) is simultaneously the engine adapter set, four native server
  implementations, WS echo, route/filter assembly, and lifecycle; adding an engine requires 3 changes and
  adding a scenario requires 4.
- ⚠ [Medium] `BenchFork`'s (290 lines) `main` carries four roles: suite orchestration, fork lifecycle,
  child-process entry point, and classpath discovery.
- ⚠ [Medium] CLI layering itself is clear, but `CliModule` dispatches by **class-name string** (`:100`
  `getSimpleName().equalsIgnoreCase(commandName + "Command")`), so a rename/move silently mismatches; the
  static `container` field and the `dispatch(container, args)` parameter are always identical (pure
  redundancy).
- ⚠ [Medium] JMH and CLI are completely disconnected: JMH results never enter `bench_results`, there is no
  `jmh` command, yet the protocol document calls JMH a decision-grade input.
- [Medium] The module **has no `src/test`** (the other 4 modules do), while comparison algorithms,
  formatting, frame encoding/decoding, percentiles, etc. are all pure logic that "should have tests".

**Consistency**
- ⚠ [High] `ServerHarness.bare()` (`:324-331`) uses `System.setProperty` to switch the JDK
  `HttpServerProvider`, but that provider is **statically cached** in the JDK (javap:
  `getstatic provider; ifnull → return`, parsing the property only on first resolution). `SuiteCommand`
  iterates `--engines` within the same JVM, so when `jdk-native,robaho-native` appear together, the second
  engine is silently measured with the first engine's numbers and mislabeled.
- ⚠ [Medium] `CompareCommand`'s regression detection **does not affect the exit code** (`:202-208` only
  prints `⚠ REGRESSIONS DETECTED`, still exits 0), so CI cannot gate on it; and
  `(t.score()-f.score())/f.score()` produces ±Infinity/NaN when the baseline is 0, reporting "all failed"
  as an improvement.
- ⚠ [Medium] The 5 result carriers (`Result`, `BenchRunner.IterationResult`, `BenchmarkResult`,
  `SuiteCommand.SuiteResult`, `BenchmarkRun`) have overlapping fields; `scoreError/unit/mode/runs` are
  **write-only**; `BenchmarkResult.of(...)` has 10 positional arguments with magic values `0, "req/s"`
  wedged in.
- ⚠ [Medium] Three DB access styles coexist (Orm entity API / hand-written SQL + `list(Class)` /
  hand-written SQL string building), and `ListCommand` branches into two `db.query` calls for the same
  condition.
- ⚠ [Medium] `--output` semantics are inconsistent (RunCommand always JSON, SuiteCommand always Markdown);
  four table styles; `RunCommand` is the only command not using `BenchFormat.rps()` (`1200000` vs
  `1.20M`); exit codes have no contract; user input errors and internal errors both print a stack trace.
- ⚠ [Medium] The mode→Mode→Scenario mapping is copied three times with mutually different defaults;
  `SuiteCommand` reimplements `ServerHarness`'s engine whitelist; `ServerHarness` bypasses
  `WebServer.builder()`, so it lacks core's default `ErrorHandler` (numbers for 413/400-class scenarios are
  not comparable with a real application), loses the `NOOP_SINK` fast path, and the parallel noop filter is
  redundant.
- ⚠ [Medium] Expected-response literals are maintained independently in two files
  (`ServerHarness.java:132-133` and `Http11Client.java:38-44`); a mismatch shows up only as "the engine
  reports errors 100% of the time".
- ⚠ [Medium] The 8 JMH classes have **no policy annotations at all**, so the defaults the protocol document
  claims (forks=2/1s/5+5/thrpt) land nowhere, and the README example's `-f 0` contradicts the protocol.
- ⚠ [Medium] 6 documentation drifts (WS engine scope, engine count 4 vs 7, the extension scenario missing
  `jettyHandler()`, `BenchDbModule` javadoc claiming it sets the JDBC URL while `BenchApp` actually sets it,
  etc.).
- [Low] `toUpperCase()` does not specify a Locale (inconsistent with other `Locale.ROOT` usages in the same
  file); `commitSha` is not null-checked; `toMap` relies on return order with no `ORDER BY`.

**Simplicity**
- ⚠ [High] `ServerHarness`'s `freeway()`/`jettyAdapter()`/`undertowAdapter()` (`:185-209`, `:212-236`,
  `:239-263`) are 25 lines × 3 **fully duplicated**, differing only in the one line `new XxxEngine(...)`.
- ⚠ [High] `stddev + SELECT *` re-query of the median row + `UPDATE score_error` is word-for-word
  duplicated between `RunCommand.java:131-146` and `SuiteCommand.java:160-173`; and `orm.insert(result)`
  already returns `ExecuteResult.longKey()` (already used at `RunCommand.java:88`), so the re-query +
  UPDATE can be deleted entirely.
- ⚠ [Medium] 3 benchmarks put an 11-line `reset(...)` inside the measured body
  (`HttpContextOutputBenchmark.java:107/127/146`), contradicting the `@Setup` presetting used by their
  peers (these 3 are also the compile-error sites in §2).
- [Low] Dead code: unused `coercer`/`orm` locals in `ListCommand`/`HistoryCommand`,
  `Http11Client.sendPing()` and its 1-arg constructor, `BenchRunner`'s 5-arg overload, and the 5 publishes
  in `bench/event` with no subscribers.

## 5. Consolidated Priority Fix List

**P0 (blocking, fix first)**
1. `BenchDbModule.java:35` `binder.install(...)` — core 1.5.2 has removed this API. ✅
2. The 9 `HttpContextImpl.reset(...)` calls pass an extra `http10`; while there, converge the calls into
   one fixture method. ✅

**P1 (high)**
3. Add `resetExchangeMeta()` to both adapters' `reset()` (order: clear metadata first, then
   `setCorrelationId`), and add a regression test that "a second request on the same thread cannot see the
   previous request's principal/attribute, and startTime is refreshed". ✅
4. Change both adapters' `ssl.enabled` to core's `SymbolSpec.activated` three-state semantics (including
   an error for invalid values). ✅
5. Add `isSecure()`/`sslSession()`/`remoteAddress()` to both adapters; add TLS assertions to the contract
   tests. ✅
6. kafka: delete `@Value`, and declare keys/types/defaults once (following the `HttpModuleConfig`
   pattern). ✅
7. kafka: wrap `KafkaEventSink.send` in try/catch and honor the "must not throw" contract. ✅
8. kafka: pick one of the two semantics for DLQ and `poison-policy=fail` (change the code or the README),
   and add tests. ✅
9. Extract the ≈200 lines of duplication between the two HTTP adapters (move into core:
   `SymbolSource.systemProperties()`, gzip/Accept-Encoding detection, correlation-ID sanitizing, snapshot
   utilities, `AbstractWebSocketSession`). ✅
10. benchmark: merge `ServerHarness`'s three assembly methods; `bare()` refuses to switch providers within
    the same JVM. ⚠

> **Status update (2026-09-13, with the fix commits)**: the ✅/⚠ above are the plan markers at audit time,
> not implementation status. **Implemented and committed**: 1, 2 (`2eaef6b`, `92fcea5`); 3, 4, 5 (jetty
> `3d1cffd`, undertow `47f8d09`); 6, 7, 8 (kafka); 10's `bare()` guard and the merging of the three
> assembly methods (benchmark). Among them, **9 was confirmed to be an intentional shape**—the two
> adapters each implement their own and each stay self-contained, and the duplication is not merged;
> therefore §6 "Intentional Differences Recommended to Keep" remains valid. After that, three P2 items
> landed: Undertow's `readTimeout=0` semantics and `MAX_HEADER_SIZE` aligned with core, Undertow's dead WS
> oversize checks removed, Jetty's `close()` restores the interrupt flag, benchmark's `Locale.ROOT`; after
> that, four benchmark items landed: scenario payload single-sourcing (the server response and the client
> expectation use the same constant), the assembly path carrying core's default `ErrorHandler`, the three
> engine assembly methods merged, and `compare` queries gaining `ORDER BY` (plus dead-code cleanup of
> `sendPing`/the 1-arg constructor/unused locals); another round converged the result carriers: report rows
> reuse `IterationResult` (so the error count enters the summary table), `forHttpIteration` replaces the
> magic-number `of(...)`, the two commands use the primary key returned by insert + a shared `medianIndex`
> (deleting the two copies of "stddev + re-query + UPDATE"), and an end-to-end test for the CLI was added.
> The remaining items are P2/P3 in §5.

**P2 (medium)**: unify module class naming/registration (`KafkaModule`'s `final`/`id`/`primary`/hook id);
align contract-test granularity and naming; merge benchmark package structure and converge the result
model; extract a repository for DB access; align JMH policy annotations with the protocol; `ServerHarness`
switches to `WebServerBuilder` (landed, see below); Undertow `MAX_HEADER_SIZE` aligned to 8192; Undertow
dead oversize checks removed; unify TLS helpers; lift `JettyWebSocketBridge` to top level;
`JettyHandle.close()` restores the interrupt flag; hikari config-fidelity warnings and `close()` semantics
declared; benchmark tests added.

**P3 (low)**: dead-code cleanup (`responded()`, `completionCallback()`, unused locals and overloads,
`bench/event` or add subscribers), `Locale.ROOT`, null checks, `ORDER BY`, javadoc wording, etc.

**(2026-09-13, another round: hikari semantic difference landed)** All three recommendations of §4.4 have
been implemented: core adds `Pool.invalidate(PooledConnection)` (`PoolDefault` physically destroys +
releases the quota; the two paths—`Database.transaction`'s state restoration and `BatchQuery`'s autoCommit
restoration—now go through it), the adapter implements destroy semantics with
`HikariDataSource.evictConnection`; `release` becomes a no-op for an already-invalidated handle—HikariCP
throws an NPE on its reset path when a proxy is closed after its entry has been evicted, and this
phenomenon was actually reproduced in tests, so this is not defensive code but a contract requirement.
§4.4's medium items: duration rewriting / `cleanInterval` not mapped / `longLeased` always 0 / `close()`
not draining are all written into the class javadoc; tests grew from 13 cases (including always-true
assertions and swallowed exceptions) to 19, of which `HikariPoolModule`'s primary binding selection and
leak-detection cascade, invalid values naming keys, invalidate's destroy/idempotence, and the Database
end-to-end destroy path previously had zero coverage. Low items cleaned up as well: the redundant
`hikariConfig` field deleted (`HikariDataSource` is itself the config), `requireNonNull` added to the two
public constructors, wildcard imports removed from tests. In the same round, §5 P2's module class
naming/registration unification was completed: `KafkaModule` changed to `public final`, hook id
`"kafka-sink"` → `KafkaModule.LIFECYCLE_HOOK = "freeway.kafka.lifecycle"` (aligned with core's
`freeway.<module>.<thing>` convention), and the javadoc now states the reason why "adapter-owned types are
not marked `.primary()`". The remaining P2/P3 are unchanged.

**(2026-09-13, another round: benchmark CLI contract unification)** The three CLI items in §4.5
Consistency have been implemented: the five table styles converged to one renderer,
`BenchFormat.table(...)` (numeric columns right-aligned, median row bold), and rates all go through
`BenchFormat.rps()` (`17.3k`, no longer `1200000`); `Command` gains an explicit `name()`, so dispatch no
longer uses the `getSimpleName().equalsIgnoreCase(name + "Command")` string match that silently mismatches
on rename, and `CliModule.dispatch` drops the container parameter that duplicated the static field; user
input errors (bad numbers, unknown engine/scenario, invalid `--output` extension) become
`UsageException`: one line `Error:` + usage hint + exit code 1, no stack trace, while internal errors still
keep the trace. `--output` previously wrote without validating—`run --output=report.md` would write JSON
into a `.md`; now it validates by extension (`run`→`.json`, `suite`→`.md`). While there, a real defect was
fixed: `run` used to insert the run row first and parse engine/scenario afterwards, so a usage error left a
half-finished row in the database; now it validates before writing to the database. Verification: all five
commands actually run (`run`/`suite`/`list`/`history`/`compare` outputs and report files all use the same
renderer), four kinds of usage errors actually run and are confirmed to have no stack trace and exit code
1, and `BenchCliTest`'s 6 cases cover the command-name set, table rendering, bad flags, and `--output`
extensions.

**(2026-09-13, another round: JMH policy annotations aligned with the protocol)** §4.5's "the 8 JMH
classes have no policy annotations at all, the defaults claimed by the protocol document land nowhere, and
the README example's `-f 0` contradicts the protocol" has been fixed: the 8 classes now uniformly declare
`@BenchmarkMode(Mode.Throughput)`, `@OutputTimeUnit(SECONDS)`, `@Warmup(iterations=5, time=1)`,
`@Measurement(iterations=5, time=1)`, `@Fork(2)`—that is, the set of defaults in
`BENCHMARK_PROTOCOL.md` §3; the protocol document adds "these defaults live in the class annotations, a
no-argument invocation is compliant, the command line can override them, and numbers after overriding are
for reference only"; the README example changed to a compliant invocation with no policy arguments, and
the `-f 0 -wi 5 -i 5` example that contradicted the protocol was deleted (replaced by an explicit
local-iteration note + "for reference only"). Verification: a no-argument run prints `# Fork: 1 of 2` →
`2 of 2`, `Warmup: 5 iterations, 1 s each`, `Measurement: 5 iterations, 1 s each`,
`Benchmark mode: Throughput`; `-f 0 -wi 1 -i 1` actually runs and confirms the command line can still
override; `-l` lists 32 benchmark methods (all 8 classes present).

**(2026-09-13, another round: ServerHarness switches to WebServerBuilder)** The item in §4.5 "bypasses
`WebServer.builder()`" has landed: `freewayWith(...)` changed to `WebServerBuilder.builder()` +
`.engine(...)/.config(...)/.cors(disabled)/.health(disabled)` + per-item
`.route(...)/.webSocketGroup(...)`. There are two benefits, both named by the audit: ① the event sink uses
the builder's default `NOOP_SINK` sentinel, so `WebServer` has `publishEvents=false` and no longer
constructs an event object per request for "a server nobody observes" (previously it passed `event -> {}`,
the sentinel comparison failed, and the fast path was lost); ② the default error mapping is appended by
the builder, and the harness no longer passes `ErrorHandlers.defaultHandler()` itself, consistent with
`HttpModule`'s contribution semantics (custom first, built-in default always last). CORS/health are still
explicitly disabled (the scenario sends no `Origin` and probes no health endpoint, since leaving them on
would mean every engine is measuring per-request overhead unrelated to the scenario), and the reason is
written in a code
comment. While there, a documentation error in core was fixed: the `WebServerBuilder` example wrote
`WebServer.builder()`, but core has no such method (core commit `593ac00c`).

**(2026-09-13, another round: three shared seams carved out by "does it contain engine API")** After a
normalized diff + method-level pairing of the two adapters, three blocks that contain no engine API and
**each also have a copy in the built-in engine** were moved down into core (§4.3's consistency conclusion
does not conflict with §6's "each implements its own"—these three blocks are not duplication between the
adapters but missing support in core):

- `Compression` (`freeway-http`): `acceptsGzip(List/String)` + `gzip(byte[])`, placed next to
  `MediaTypes`. The built-in engine `HttpContextImpl`'s `acceptsGzip`/`qValueIsZero`/`gzip` and the two
  adapters' same-named methods (100%/94% identical at method level) all now call it; the unified semantics
  are "no header → no compression, `gzip` on, `gzip;q=0` rejected, `q` name case-insensitive, malformed q
  treated as accepted". core adds `CompressionTest` with 7 cases.
- `AbstractWebSocketSession` (`freeway-http.websocket`): the request-identity + metadata half of a session
  (the ~91 contiguous byte-identical lines). core's `WebSocketSessionImpl` and the Jetty/Undertow sessions
  now all extend it; the two adapter sessions shrank from 232/348 lines to 124/240. core adds
  `AbstractWebSocketSessionTest` with 6 cases (case-insensitive header lookup, immutable snapshot, empty
  collections, correlationId fallback, closeReason truncation).
- `SymbolSource.systemProperties()` (`freeway-ioc`): the three 22-line identical implementations in
  Jetty/Undertow/HikariCP merged into one; core adds 2 tests.

While there, a real defect was fixed: the three shared `closeReason` implementations used to hard-cut at
exactly 123 bytes, and cutting in the middle of a multi-byte character produced a replacement character
that re-encoded to 3 bytes, making the measured truncation result 125 bytes—over the 123-byte close-frame
limit. It now falls back to a code-point boundary.

Explicitly not extracted (written into §6): the `*Handle` records (the difference is each one's
graceful-shutdown API), TLS helpers, the 404 body, the `readTimeout=0` spelling, per-request dispatch, and
WS frame-limit rejection timing. **(2026-09-13, another round: contract-test duplication governance)** Of
§5 P2's "align contract-test granularity and naming", the three most duplicated suites have converged: a
new `freeway-http-adapter-testkit` module (not an application dependency, test-only) containing
`EngineFixture` (config/assembly/resource lookup), `Pipelines`/`HttpClients` utilities, and three contract
base classes `CompressionContract`, `ContextContract`, `RemoteRpcContract`; the two adapters'
corresponding test classes each become ~35-line subclasses declaring only their own engine. Adapter tests
**+86 / −994 lines**, test counts unchanged (jetty 27 / undertow 28). Reverse check: after changing the
CORS in the shared `Pipelines` to enabled, **both adapters'** contract tests turned red at the same time (1
failure + 12 errors each, visible under `-fae`), proving that both sides really run the shared code rather
than keeping their own copies. The remaining near-pairs (`*WebEngineContractTest` 68.7%,
`*WebSocketProbeTest` 62.4%, `*EngineConfigTest` 85%) are not merged: the differences are precisely the
engine-specific parts (h2c, WS frame-limit rejection timing, adapter-specific config keys), written into
§6.

**(2026-09-13, another round: `JettyWebSocketBridge` lifted to top level)** One item of §5 P2 has landed:
the 110-line nested `JettyWebSocketBridge` inside `JettyWebEngine` became its own file, and the engine went
from 568→427 lines, retaining only transport start/stop and upgrade routing. The extraction exposed a real
constraint, written into the class javadoc: Jetty's frame factory resolves listener lifecycle methods
reflectively, and **the class must be public**; package-private causes a direct `IllegalAccessException`
and a 500 on upgrade—the WS probe tests with two real sockets caught it on the first try (this is also what
the audit meant by "needs real WS runs" to verify). Verification: all 27 jetty cases green (including two
raw-socket WS probes), and the full ext suite green.

**(2026-09-13, another round: TLS settings and context construction shared)** §5 P2's "unify TLS
helpers" has landed: core adds public `SslSettings.from(SymbolSource)` (all `freeway.http.ssl.*` keys +
three-state decision) and `SslContexts` (promoted from `internal.SslContextFactory`, with public
`build`/`parameters`, including SNI, truststore, protocols/cipher suites); `internal.HttpModuleConfig.Ssl`,
`internal.SslContextFactory`, and `internal.SniKeyManager` are deleted or moved into the root package
accordingly. The two adapters now read `SslSettings`: Jetty keeps its own `ssl.key-password`/`key-alias`
and Jetty's SslContextFactory, and **Undertow deletes its self-written 40-line JSSE construction** (now
calling `SslContexts.build`)—which also fills in the SNI/keystore-type detection/empty-password rules it
previously lacked, matching the built-in engine. During this, a real defect was found and fixed:
`SymbolSource.systemProperties()` (the standalone source added in the previous round) implemented only
`resolve(String)`, and threw "has no parser" for adapters reading via `SymbolSpec`—**the container path was
fine, direct construction failed**; it now wires `CoercerDefault` internally, consistent with the container
chain. The two adapters' config tests also switched to the testkit's shared `Symbols.of(Map)` (also
resolving specs with container semantics), because the previously hand-written doubles also implemented
only string reads.

**(2026-09-13, another round: `BenchFork` split by role + mode mapping single-sourced)** Two items of
§4.5 landed at once: ① `BenchFork` (291 lines, four roles) split into `BenchFork` (93 lines, role dispatch
+ server/client child-process entry points), `ForkedRunner` (132 lines, run loop/interval/median),
`BenchProcesses` (143 lines, JVM and classpath discovery, `READY` handshake, log reading and cleanup); ②
the audit's "mode→Mode→Scenario mapping copied three times with mutually different defaults" converged
into `BenchMode`—the three entry points share spelling, aliases (`long`), labels, and the forked scenario
convention; an unknown spelling (`--mode=wl`) is no longer silently measured as keepalive but becomes a
usage error naming `--mode` (on the CLI side)/a direct child-process failure (on the fork side). The module
previously had zero tests for this code; now `BenchModeTest`(4) and `BenchProcessesTest`(3) are added.
Verification: a real fork completes two rounds + median; CLI `--mode=ws --scenario=ws_echo` runs; both
rejection paths actually run; the benchmark module's 16 cases and the full ext suite are green.

**(2026-09-13, another round: scenario single-sourcing + two real defects)** §4.5's root cause of "adding
a scenario requires 4 changes" has been eliminated: a new `ScenarioSpec`
(method/path/content-type/response-body/echo, json, websocket flags) is the single scenario table, read by
`ServerHarness`'s Freeway routes and all three JDK/Undertow/Jetty handlers, and the client's
`RequestPattern.of(spec)` is derived from it too; adding a scenario = one enum constant + one table entry.
`ServerHarnessTest` adds two cases pinning "each engine's real response == spec" (bytes and content-type)
and "the echo path of all four engines"; the result immediately caught two real defects that each switch
had masked: ① Undertow's echo handler did a blocking read on an I/O thread (UT000126), so
`undertow-native --scenario=echo_body` returned 500 for every request before; ② the generalized handler
also read the request body in GET scenarios, causing all of undertow's JSON/PING to fail. Now echo goes
through Undertow's `BlockingHandler` (worker thread), while fixed-length responses still use the
non-blocking sender. Four engines × {ping,json} and echo all ran with 0 errors.

**(2026-09-13, another round: DB access styles converged)** §4.5's "three DB access styles coexist" has
landed as `BenchRepository` (`bench.db`): `Orm` entity insert/lookup, `list`'s assembled WHERE (previously
the same condition branched into two `db.query`), `history`'s parameter-list JOIN, `compare`'s two
duplicate result queries and inline join, and `run`/`suite`'s `UPDATE score_error` are all pulled into one
class; the five commands no longer build SQL themselves or `new Orm(...)` themselves.
`BenchRepositoryTest` adds 3 cases covering branches previously reached only end-to-end: newest-first and
engine filtering, results read in insertion order, dispersion landing on the median row, the window JOIN's
optional filter, and the "best historical comparable run" baseline rule. Verification: benchmark 21 cases,
all five commands actually run against a real SQLite file (run/list/history/compare all green).

**(2026-09-13, another round: JMH results enter the database)** §4.5's "JMH and CLI are completely
disconnected" has landed: a new `bench jmh` command
(`--include/--forks/--warmup/--iterations/--time`), running JMH in-process and writing each benchmark
method as one `BenchmarkResult` row (JMH's own `Score ± Score Error`, unit, mode; the three write-only
columns `score_error/unit/mode` now have a real source), and the run row records the JMH parameter block
(engine=jmh, scenario=include, concurrency=forks, requests=iteration count, warmup, runs=method count).
`list`/`history`/`compare` can therefore read microbenchmark scores directly (actual run:
`history --bench=<class.method>` shows 308.8k and -12.4% comparison). Two edge cases were handled in the
implementation: JMH's single-iteration score error is NaN (column NOT NULL) → recorded as 0; `--include`
with no match → a usage error naming that flag. Also removed JMH's own `jmh-result.text` dump (scores
going into the DB and the terminal are enough). Tests: `JmhCommandTest` 2 cases (an end-to-end 3-row
assertion from a real JMH run to database insertion + the no-match usage error).

**(2026-09-13, another round: `bench/event` gets subscribers + two low items verified)** The audit's
§4.5 "`bench/event` or add subscribers" landed as the former: a new `BenchEventListener` (DEBUG level, the
console already prints the same progress) registered in `CliModule` with id `bench-progress`, so the
publishes in commands now have a consumer, and it is also the ordering anchor for embedders appending
subscriptions; `BenchEventListenerTest` uses the CLI's own container to prove all three events reach a
contributed subscriber. In the same period two P3 items were verified: `wsMaxMessageSize` **no longer
appears anywhere** in the engine/session (cleaned up in an earlier round), and likewise
`responded()`/`completionCallback()` (no hits in a whole-module grep)—both can be removed from the list.

**(2026-09-13, another round: re-check of core's three "intentionally kept" items)** Evidence was
re-checked for three items intentionally kept outside §6: ① `WebServer` constructor ladder: the
package-private 5-arg constructor (only one more `readinessProbe`) **has no callers at all**—core's
`HttpModule` and `WebServerBuilder` both go straight to 6 args, and 4 args is for external
adapters/tests; it has been deleted (core commit), collapsing the ladder from 4/5/6 to 4/6. ②
`HttpContextImpl.reset`'s 10 args: there are only two call sites (`Http1xSession`, `Http2Session`), all ten
are required values, the method itself is package-private, and switching to a parameter record would add an
allocation on the per-request reuse path; the rule "three or more optional inputs use a parameter record"
targets the public assembly surface—**keep**. ③ `ServiceRegistry.drainWindow()`: read by
`RegistryLifecycleHook`, overridden by `RegistryDrainTest`; it is the established SPI shape of "a default
implementation so custom registries need not implement it"—**keep**.

**(2026-09-13, another round: adapter test assembly unified, first half)** The point recorded in the
previous paragraph is half done: the testkit's `Pipelines` changed to a builder input (routes + WebSocket
groups; CORS/health still explicitly disabled), and `EngineFixture.start(...)` now goes through
`WebServerBuilder`—the three shared contracts (compression/context/remote-rpc) now have the same assembly
shape as a standalone application (NOOP sink sentinel + default error handler), and both sides' tests are
green.
**The second half not done**: the six per-adapter test classes still contain ~15 direct
`new WebServer(engine, config, event -> {}, pipeline)` calls (`JettyWebEngineContractTest` 5,
`UndertowHttpContractTest` 6, `JettyTlsHttp2Test` 2, `UndertowTlsTest` 2, `UndertowTransportLimitsTest` 1,
`JettyWebSocketProbeTest` 2), each carrying engine-specific fixtures (TLS properties, transport limits,
raw-socket WS probes), and the pipeline is an inline-constructed `RequestComponents`; migrating requires
changing each fixture to `Pipelines` + builder shape—pure test-side consistency work that does not affect
released behavior, left for the next round (each migrated file must run the module green).

**(2026-09-13, another round: Kafka cross-JVM link investigation and fix)** The user asked to "install
Kafka, run the cloud integration tests, and make cross-JVM events go through Kafka", which uncovered and
fixed a real defect: `KafkaEventSink` wrote records into the **local dispatch topic** (a string topic such
as `orders.created`, or the event's simple class name for class dispatch), while `KafkaSubscriber` only
polled the configured `freeway.kafka.topics`—production and consumption were never on the same topic, so
cross-JVM events could never arrive (measured: records written via the bus path landed on
`freeway-xjvm.greet`/`Probe`, while the subscriber watched only `freeway-xjvm`, offset 0; bypassing the bus
with a direct `sink.send("freeway-xjvm", ...)` did persist, proving the sink and broker themselves were
fine). Fix: the sink writes the configured bridge topic and carries the local topic in a new header
`X-Event-Topic`; the subscriber restores the local topic from that header (falling back to the Kafka topic
name when the header is absent, compatible with old producers). **Verified with two JVMs**: A publishes a
class event and a string-topic event; B's local subscribers receive `XjvmOrder[orderId=order-42, amount=7]`
and `hello-from-jvm-A` respectively.

At the same time two problems were found, and **both were fixed in the same round** (ext `79b6104`):
① `KafkaEventSinkIntegrationTest` used to be a **false positive**—the same-JVM `bus.subscribe(...)` it
asserted was satisfied by the synchronous local dispatch at publish time, and it passed even with no record
on the broker (which also explains why the four cases stayed green after "4 skipped → 0 skipped" yet
proved nothing about the wire). Now publishing and subscribing are in **two containers** with different
origins (`it-producer`/`it-consumer`, otherwise suppress-own would discard the bridge record as its own),
and the subscriber side can pass only if the record really crossed the broker; reverse check: change the
subscription topic to a non-matching name → all four cases turn red (the old version stayed green at this
point), and after restoring, the 4 cases are green against a real broker.
② `freeway.kafka.allowed-event-types` being empty **discarded all consumed records** (including
string-topic events, whose type header is `java.lang.String`), which, when measured, was what made the first
cross-JVM attempt receive nothing. Now `KafkaSubscriber` warns at startup when the allowlist is empty
(naming the key and the `java.lang.String` rule, and explaining that "the symptom will be no messages on
the broker"), and `KafkaConfig`'s class javadoc gains a section on "three keys that must agree across
nodes" (topics=bridge topic, allowlist=accept list, client-id=node identity for suppress-own; sharing it
between two nodes makes them swallow each other's events).

**(2026-09-13 addendum: cross-JVM bridge test enters the repository)** `CrossJvmEventTest` +
`CrossJvmRole`: the test starts **two independent JVMs** (one publisher, one subscriber), sharing nothing
but the broker, creates a fresh bridge topic on every run (concurrent runs and old offsets do not
interfere), asserts that class events are reconstructed on the consumer side and string-topic events
arrive by local topic. Gating is the same as the in-place contract tests (`FREEWAY_TEST_KAFKA`): no
broker → skip, dead address → red (measured TimeoutException), real broker → green (module 43 cases, 0
skipped). This is exactly the kind of test that independently finds "bridge topic mismatch".

Verification: core `mvn -o clean test` fully green (http 411→424 cases, ioc 257→259 cases), all five ext
modules green, with the two adapters' compression/WS probe tests as the regression net.

## 6. Intentional Differences Recommended to Keep

- Undertow does not map `maxConnections` (no corresponding capability, commented at `:169`); neither
  adapter maps `writeTimeout` (documented in README:128; recommend adding a one-line code comment on the
  Jetty side).
- Jetty alone has `freeway.http.http2` (h2c); Undertow explicitly does not enable h2c.
- WS frame oversize rejection timing and `onError`/`onClose` differences (documented in README:163-169).
- Adapter-specific config keys: `freeway.http.http2`, `freeway.http.undertow.dispatch-io`,
  `freeway.http.ssl.key-password`/`key-alias`, `freeway.db.pool.leak-detection`.
- The two engine modules are independent **at runtime** (an application depends on only one)—this is the
  established architecture in the README and AGENTS.md, and is a different matter from "source
  duplication": moving shared utilities down into core does not break it.

## 7. Verification Status

- ✅ Independently verified by me (read source/bytecode/ran the build): the clean-build failure and its two
  root causes, the missing `resetExchangeMeta`, `ssl.enabled` semantics, the three missing transport
  metadata methods, gzip detection drift, the three copies of `systemProperties`, the byte-identical
  blocks and test duplication, kafka's dead `@Value` annotations and three default declarations, the
  `EventSink` no-throw contract, the DLQ policy contradiction, the `MAX_HEADER_SIZE` difference, the
  benchmark split package, and the module class naming inconsistency.
- ⚠ From the module deep review, not verified item by item by me (marked in the body): Undertow's dead
  oversize checks and the full trigger chain of `readTimeout=0`, TLS fork details, Jetty's
  `NetworkConnectionLimit` semantics, Hikari duration rewriting and close semantics, and the benchmark JMH
  policy/CLI consistency items.
- **One correction to a deep-review conclusion**: hikari's "polluted connection flows back" high risk was
  downgraded to "semantic difference"; the reasoning is in §4.4.

## 8. Appendix: Verification Commands

```bash
# Real build state (clean is mandatory; incremental builds hide API drift)
mvn -o -B clean test

# Formatting gate (bound to the verify phase; mvn test does not trigger it)
mvn -o -B spotless:check

# Quantify duplication across adapters
diff <(sed 's/Jetty/ENGINE/g; s/jetty/engine/g' A.java) \
     <(sed 's/Undertow/ENGINE/g; s/undertow/engine/g' B.java)

# Compare the core API surface
javap -p -cp ~/.m2/repository/com/jujin8/freeway/freeway-ioc/1.5.1/freeway-ioc-1.5.1.jar com.jujin.freeway.ioc.Binder
javap -p -cp ~/.m2/repository/com/jujin8/freeway/freeway-ioc/1.5.2-SNAPSHOT/freeway-ioc-1.5.2-SNAPSHOT.jar com.jujin.freeway.ioc.Binder
```

## 9. Closing Status (2026-09-13)

The entry criterion of this audit is the three axes "structure / consistency / simplicity", not "fewer
lines". The categories below follow the final state; the round-by-round update notes in the body are kept
at §5, §6 and the ends of §4.x as process records.

### 9.1 Landed

| Topic | Result | Key verification |
|---|---|---|
| P0 clean build | benchmark follows core 1.5.2 API (module tree + `HttpContextImpl.reset`) | `mvn -o clean test` fully green (was falsely green for a long time) |
| Two HTTP adapters (jetty/undertow) | exchange metadata reset for the pooled context, `ssl.enabled` three states, `isSecure`/`sslSession`/`remoteAddress`, `read-timeout=0`, header budget, dead WS oversize checks, `close()` interrupt flag | side-written tests + real TLS/raw-socket WS probes |
| kafka | single declaration of 12 keys/defaults, `send` honors the no-throw contract, DLQ and `poison-policy` semantics, `final` + `freeway.kafka.lifecycle` hook id | 42 cases (4 skipped) |
| hikari | `Pool.invalidate` landed as physical destroy (new core SPI), the four forks—duration rewriting/`cleanInterval`/`longLeased`/`close()`—written into javadoc, tests 13→19 cases | reverse check: reverting to "close the handle" turns three cases red |
| benchmark | package structure unified (`benchmarks`→`bench.*`), CLI contract (one table/one number/usage errors/exit codes/`--output` extension), JMH policy annotations aligned with the protocol, `ServerHarness` goes through `WebServerBuilder`, `BenchFork` split by role, scenario single-sourcing `ScenarioSpec`, DB statements converged in `BenchRepository`, `bench jmh` results into the database, event subscribers | real fork / four-engine actual runs / reverse check every round |
| core reuse seams | `Compression`, `AbstractWebSocketSession`, `SymbolSource.systemProperties()`, `SslSettings`/`SslContexts`; the `closeReason` UTF-8 truncation defect | full core + both adapters' contract tests |
| test duplication | `freeway-http-adapter-testkit`: three 98-100% identical contracts converged, adapter tests −994 lines; testkit assembly goes through `WebServerBuilder` | reverse check: changing the shared `Pipelines` turns both sides red at the same time |
| core kept-item re-check | dead `WebServer` constructor deleted (4/5/6→4/6) | core clean test |

### 9.2 Intentionally Kept (not done; reasoning in the body)

- The two adapters implement their own per engine API and each stay self-contained: transport start/stop,
  per-request dispatch (Undertow's worker handoff), response mapping, TLS assembly, `*Handle` record types,
  404 body, `readTimeout=0` spelling, WS frame-limit rejection timing.
- `AbstractHttpContext` / `HttpContextImpl.reset` 10 args (two internal call sites, ten required, per-request
  hot path).
- `ServiceRegistry.drainWindow()` default method (the established SPI shape; the hook reads it, tests
  override it).
- `ServerHarness` is no longer split per engine: scenarios are single-sourced, assembly goes through the
  builder, the WS bridge is lifted to top level, and what remains is "the file is long". If a fifth engine
  is added in the future, refactoring into "a factory + one `BenchServer` implementation class per engine"
  would be more worthwhile.

### 9.3 Fixed (2026-09-13 addendum: Kafka cross-JVM link)

- **The bridge topic and the subscription topic used to disagree** (`b49fa9e`): the sink wrote the local
  dispatch topic (string topic or event simple class name), and the subscriber only polled
  `freeway.kafka.topics` → cross-JVM events could never arrive. Now the sink writes the configured bridge
  topic and carries the local topic in the `X-Event-Topic` header, and the subscriber restores from that
  header; two-JVM actual runs deliver class and string-topic events.
- **The real-broker contract test used to be a false positive** (`79b6104`): publishing/subscribing now
  live in two containers (different origins), and the reverse check (subscription topic mismatch) can turn
  all four cases red.
- **An empty `freeway.kafka.allowed-event-types` used to silently discard everything** (`79b6104`): there
  is now a startup warning, and the `KafkaConfig` javadoc states the "three keys that must agree across
  nodes" (topics / allowed-event-types / client-id).

### 9.4 Remaining (no effect on released behavior)

- About 15 places in the six per-adapter test classes still call
  `new WebServer(engine, config, event -> {}, pipeline)` directly (`UndertowHttpContractTest` 6,
  `JettyWebEngineContractTest` 5, `JettyTlsHttp2Test` 2, `UndertowTlsTest` 2, `JettyWebSocketProbeTest` 2,
  `UndertowTransportLimitsTest` 1): each carries engine-specific fixtures, and migrating to `Pipelines` +
  builder requires file-by-file work and running the module's regression after each. This is a test-side
  consistency issue (these tests' servers construct an extra per-request event object and miss the default
  error handler) and changes no release artifact.

### 9.5 Addendum (2026-09-15): core converges the symbol chain; the seam in §9.1 got a new name

The core reuse seam recorded in §9.1 of this document, `SymbolSource.systemProperties()`, has been removed
by core: system properties became one tier of `SymbolProvider` (`SymbolProvider.systemProperties()`,
`order()=TIER_SYS_PROPS`), and the symbol chain collapsed into one implementation
`SymbolSource.of(Coercer, SymbolProvider...)`. The three no-container constructors each changed by one line
accordingly (Jetty / Undertow pass their existing `Coercer`, Hikari passes `new CoercerDefault()`, and for
this the hikari module declares `freeway-commons` directly). There is only one semantic change, and it
aligns with the container path: standalone assembly now also expands `${...}`, also resolves `SymbolSpec`
with the passed `Coercer`, and also accepts `register(SymbolProvider)`.
