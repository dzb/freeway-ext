# freeway-ext Adapter Contract Audit (core 1.5.7-SNAPSHOT)

> A contract audit of the 8 modules of `freeway-ext` using the same method as the core audit: not the docs'
> self-description, only (a) a **clean** build against the just-installed core snapshot, and (b) a line-by-line
> comparison of adapter code with the core SPI contracts. Every criterion points to a file and a behavior.

## 0. Method and Baseline

- **Clean build** (an incremental build does not count as evidence):
  ```
  cd ../freeway-framework && mvn -o -DskipTests -Dgpg.skip=true install
  cd ../freeway-ext       && mvn -o -Dgpg.skip=true clean test
  ```
  Result: all 8 modules green — testkit 0, Jetty 39, Undertow 35, Kafka 51 (5 cases skipped per
  `FREEWAY_TEST_KAFKA`), Consul 13 (2 cases skipped), Hikari 21, Benchmark 41.
- **Counterexample of an incremental build**: the first run had no `clean`, and Undertow's tests reported at
  **runtime**: `IllegalAccessError: failed to access class com.jujin.freeway.http.SslContexts`; after `clean`, the
  same defect reported at **compile time**: `SslContexts is not public in com.jujin.freeway.http`. Maven saw that
  the ext sources had not changed and skipped compilation, leaving old classes in `target/classes` — exactly what
  ext AGENTS warns about: "an incremental compile keeps the previous class".
- Audit subjects: `HttpEngine` (Jetty, Undertow), `Pool` (Hikari), `ServiceRegistry` + `ServiceDiscovery`
  (Consul), the Kafka event plane, and the shared `freeway-http-adapter-testkit` contracts.

## 0.1 Fixed This Round (core side)

| Item | Fix | Evidence |
|---|---|---|
| `SslContexts` narrowed to package-private | Restored `public` (its javadoc already said "Undertow builds its SSLContext from here") | ext clean build went from red to green |

This was an over-narrowing in the core 1.5.7 audit's "closing the public surface by role": the rationale was
"only the same-package `HttpModule` uses it in-repo", but **no in-repo caller is not evidence** — the Undertow
adapter in `freeway-ext` must use the same keystore/SNI logic when terminating TLS, and the compiler is how the two stay consistent.

## 0.2 Fixed This Round (ext side)

| Item | Fix | Verification |
|---|---|---|
| `maxHeaderBytes` on both engines | Undertow: `MAX_HEADER_SIZE` + h2 header-list settings; Jetty: plaintext/HTTPS/h2c share `HttpConfiguration.setRequestHeaderSize` (the plaintext branch no longer uses a bare `ServerConnector`) | `HeaderBudgetContract`, 2 cases per engine |
| testkit contracts | Added `HeaderBudgetContract`; `ContextContract` gained "request header names are all lowercase" and "204 carries no `Content-Length`" | Both engines pass |
| Consul readiness | `ConsulClient.register(instance, ready)` → `passing`/`critical`; `markReady` → check pass/fail; `ConsulServiceRegistry` covers both seam methods | `ConsulClientTest` gained 4 cases |
| Stale comment | `ExchangeMetaDefault` → `ExchangeMetaImpl` | — |

ext clean build: **all 8 modules green, Jetty 43, Undertow 39, Kafka 51 (5 skipped), Consul 17 (2 skipped),
Hikari 21, Benchmark 41, testkit 0**; `spotless:check` passes.

## 1. Conclusions

| Severity | Problem | Location | Status |
|---|---|---|---|
| Blocker | `SslContexts` visibility prevented ext from compiling | core `http/SslContexts` | ✅ Fixed (core) |
| High | New config field `maxHeaderBytes` is ignored by both engines and not reported | `UndertowHttpEngine`, `JettyHttpEngine` | ✅ Fixed |
| High | Consul did not implement the new readiness seams; registration immediately reports `passing` | `ConsulServiceRegistry`/`ConsulClient` | ✅ Fixed |
| Medium | testkit did not cover the new contracts (header budget, lowercase headers, bodyless-status framing) | `freeway-http-adapter-testkit` | ✅ Fixed |
| Low | Comment still names the old `ExchangeMetaDefault` | `JettyWebSocketBridge` | ✅ Fixed (one line) |

**Overall judgment**: the adapters themselves are no lower in quality than core — shared rules are really shared (`ResponseFraming`, `Compression`,
`AbstractHttpContext.readBody`), handle ownership is guarded (Hikari's foreign-release / invalidate→release), and
testkit is the correct shape of "contract as API". The lost points concentrate on **propagation of new contracts to ext**: a new config field, a new
readiness method — both are "core added it, the adapter did not follow, and nothing would ring".

## 2. Item-by-Item Audit

### 2.1 The 12 `HttpServerConfig` Fields (AGENTS: every field is adopted or reported)

| Field | Undertow | Jetty |
|---|---|---|
| host / port | ✅ listener | ✅ connector |
| backlog | ✅ `Options.BACKLOG` (when >0) | ✅ `acceptQueueSize` |
| shutdownGrace | ✅ handle | ✅ `setStopTimeout` |
| maxBodySize | ✅ `MAX_ENTITY_SIZE` + context `readBody` | ✅ context `readBody` |
| readTimeout | ✅ `IDLE_TIMEOUT`/`REQUEST_PARSE_TIMEOUT` (0→-1) | ✅ `setIdleTimeout` |
| maxConnections | ⚠️ no counterpart, **reported with a startup WARN** | ✅ `NetworkConnectionLimit` |
| writeTimeout | ⚠️ no counterpart, **reported with a startup WARN** | ⚠️ no counterpart, **reported with a startup WARN** |
| compression | ✅ context `output()`, via `ResponseFraming.shouldGzip` | ✅ same |
| receiveBufferSize / sendBufferSize | ✅ socket options (when >0) | ✅ accepted buffers (when >0) |
| **maxHeaderBytes** | ✅ `MAX_HEADER_SIZE` + h2 `HTTP2_SETTINGS_MAX_HEADER_LIST_SIZE` (same value) | ✅ all three branches share `HttpConfiguration.setRequestHeaderSize` |

Both rows violate "every field is adopted or reported", and moreover the comment in the Undertow row is itself already a stale fact (of the same class as
"the docs say a falsehood" in the core audit). **Fix**:

- Undertow: `.setServerOption(UndertowOptions.MAX_HEADER_SIZE, config.maxHeaderBytes())`;
  if the h2 header-list limit has a corresponding option, map it as well (`javap io.undertow.UndertowOptions | grep -i header`).
- Jetty: `HttpConfiguration https = new HttpConfiguration(); https.setRequestHeaderSize(config.maxHeaderBytes())`;
  handle the h2c branch's `HttpConfiguration` the same way; if HTTP/2's `HTTP2ServerConnectionFactory` has another limit,
  set it explicitly or report it.
- Tests: add `HeaderBudgetContract` to testkit (`defaultConfig().withMaxHeaderBytes(1024)`,
  send a 2 KiB header, assert 4xx and not 500; then assert a normal header is accepted at the default value ≥16 KiB). Each of the two adapters inherits it.

### 2.2 Other Shared Contracts

| Contract | Undertow | Jetty | Criterion |
|---|---|---|---|
| `headers()` keys lowercase | ✅ `name.toString().toLowerCase(ROOT)` | ✅ `toLowerCase(ROOT)` | the same contract as core's `Headers.copyOf` |
| `maxBodySize` goes through the shared limiter (413) | ✅ `readBody` + normalizes Undertow's `RequestTooBigException` into `BodyTooLargeException` | ✅ `readBody` | core `AbstractHttpContext`/`LimitedInputStream` |
| gzip follows the shared rule | ✅ `ResponseFraming.shouldGzip` + `Compression` | ✅ same | no second gate of its own |
| bodyless statuses carry no Content-Length | ✅ removed when `allowsResponseBody()` is false | ✅ same | same meaning as core `ResponseFraming.suppressesHeader` |
| WS upgrade goes through `ExchangeHandler.websocket` | ✅ every upgrade candidate is asked | ✅ same | when nothing matches: core 403, Jetty 404 text, Undertow default page — testkit asserts only "not 101", deliberately |
| fallback for unhandled exceptions | text 500 | text 500 | only on escape paths outside the kernel error mapping; core has unified the JSON error body, so this adapter point counts as "the last hop" — recorded but not required |

### 2.3 Hikari (`Pool`)

- All contract points are covered: foreign handles are explicitly rejected (`release`/`invalidate` both validate `HkConn`), after `invalidate`
  `release` becomes a no-op (avoiding an error when closing an entry HikariCP has already evicted), `close()` delegates directly to
  `HikariDataSource.close()` and states that it does not wait for leased connections, and `stats()`'s `longLeased` being constantly 0 is documented
  (switch to HikariCP's own leak-detection).
- Fields in `PoolConfig` with no HikariCP counterpart (`cleanInterval`/`queryTimeout`) are named in the constructor comment,
  which counts as "reporting" rather than staying silent.
- Conclusion: **no findings**.

### 2.4 Consul (`ServiceRegistry` / `ServiceDiscovery`)

- Implemented: `register` / `renew` (404→false, any other non-2xx throws so core records it as unhealthy) / `unregister`
  / `drainWindow`; discovery uses `?passing=true`, synonymous with "live && ready".
- **Gap (high)**: core 1.5.7's readiness seam consists of two methods —
  `register(instance, Health)` and `markReady(instance, boolean)`. The Consul adapter covers neither:
  - `RegistryLifecycleHook` now calls `register` with `Health.starting()`; the interface's default implementation falls back to
    `register(instance)`, and `ConsulClient.register` writes the TTL check as `"Status": "passing"`
    (the comment even explains "otherwise there is a black hole between registration and the first heartbeat") — so **a not-ready instance becomes immediately
    `passing` in Consul and is discoverable**, and the window this core version was specifically meant to eliminate is back.
  - `ReadinessGate`'s `markReady(false/true)` is a default no-op, so for Consul the **whole gate is ineffective**;
    core only prints one INFO line ("bound registry is not the built-in one"), which is an explanation, not a fix.
- **Fix**:
  - `ConsulClient.register(instance, boolean ready)`: `Check.Status` written as `passing`/`critical`;
    the core hook hands over a `Health`, and the adapter is responsible for translating it.
  - Cover `ServiceRegistry.register(instance, Health)` and `markReady(instance, boolean)`:
    markReady → `PUT /v1/agent/check/pass|fail/{checkId}` (failures handled the same way as `renew`).
  - Tests: while registered as starting, discovery does not include it; after `markReady(true)` it does; the existing `ConsulClientTest`
    stub server can be extended.

### 2.5 Kafka

- In this cycle, core did not change the Kafka plane's SPI (`KafkaEvents`/bridging contract unchanged); the adapter's 51 cases (5
  env-gated) cover producer, subscription table, poller, ce-fwtopic passthrough, suppress-own, and allowed-event-types.
- The three "must stay consistent" keys listed in ext AGENTS still hold; no findings.

### 2.6 Testkit

Existing contracts: `CompressionContract`, `ContextContract` (transport honesty, TLS activation, oversized body 413, context
freshness), `RemoteRpcContract`, `WebSocketUpgradeContract`.

Gaps (by value):

1. **Header budget**: no contract covers `maxHeaderBytes` (which is exactly why both engines missed it together).
2. **`headers()` lowercase**: core states "request header names are uniformly lowercase" as a `RequestView` contract, and both adapters do it,
   but no contract pins it — a new engine could silently fail to comply.
3. **Bodyless-status framing**: `204/205/304` carry no `Content-Length`, `HEAD` keeps it — currently this relies on the shared
   `allowsResponseBody()`, but a contract should still assert the on-the-wire result.
4. Optional: the "reported" behavior of `withMaxConnections`/`withWriteTimeout` is hard to test (log assertions are brittle),
   so it is recommended not to include it in testkit.

## 3. Fix List (by priority)

1. **core**: restore `SslContexts` to public (✅ done, awaiting regression together with ext's clean build).
2. **Undertow/Jetty**: wire up `maxHeaderBytes` (required for h1; wire it for h2 where the capability allows, otherwise report) + add
   `HeaderBudgetContract`.
3. **Consul**: implement `register(instance, Health)` and `markReady`, with the TTL check status flipping with readiness;
   test the visibility change from starting→passing.
4. **testkit**: add lowercase-headers and bodyless-framing assertions to `ContextContract`.
5. **Docs/process**: add a sentence to the Build section of ext `AGENTS.md`: "validating core changes requires a `clean` build; an incremental build leaves
   old classes behind and masks ABI breaks"; change `ExchangeMetaDefault` in the `JettyWebSocketBridge` comment to the current name.

## 4. Score

| Dimension | Score | Basis |
|---|---|---|
| Positioning | 4 | each adapter does only its own transport; shared rules are really shared |
| Mechanics | 4 | guards are in place (foreign handles, invalidate→release, 413 normalization, single gzip gate) |
| Concepts | 4 | one `HttpEngine` per engine, testkit is the contract surface, no third abstraction |
| Boundaries | 3 | "propagation" of a new config field/new readiness seam has no mechanism; it relies on people remembering |
| Rules | 4 | the direction that adapters do not modify core is maintained; "reported" is used honestly |
| Docs | 3 | stale comments (8192, `ExchangeMetaDefault`); the readiness gap is completely silent |

**In one sentence**: the adapter implementation is healthy; the real defect is **core's new contracts have no propagation
channel that "rings at compile time or startup"** — an ignored field or an uncovered method is only discovered at the next manual audit.
