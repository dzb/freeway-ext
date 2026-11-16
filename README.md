<div align="center">
  <h2><img width="32" src="https://raw.githubusercontent.com/dzb/freeway/main/.github/assets/logo.png" alt="Freeway logo" align="middle" hspace="8">Freeway Ext</h2>
  <p><sub><strong>Adapters and integrations for the Freeway framework.</strong></sub></p>
  <p><sub>HTTP engines · Kafka · Fleet · Consul · HikariCP</sub></p>
  <p>
    <a href="https://github.com/dzb/freeway"><img src="https://img.shields.io/badge/core-Freeway-blue" alt="Freeway core"></a>
    <a href="https://jdk.java.net/25/"><img src="https://img.shields.io/badge/JDK-25%2B-orange" alt="JDK 25+"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License"></a>
  </p>
  <p><sub><strong>Aligned with Freeway 2.0.0</strong> · <a href="CHANGELOG.md">Changelog</a> · <a href="docs/releases/release-notes-2.0.0.md">Release notes</a></sub></p>
</div>

---

Freeway core already ships a zero-dependency built-in engine: raw sockets on
virtual threads, HTTP/1.1 keep-alive, HTTP/2 h2c/h2, WebSocket and HTTPS. It
reuses a 4 KB parser buffer and parser state across keep-alive requests, while
response writers are per response and stream body bytes through the connection's
buffered output.

The modules in this repository are opt-in: use one only when an application
needs a particular HTTP runtime, broker, registry or database pool.

<p align="center"><sub><a href="#modules">Modules</a> · <a href="#http-adapters">HTTP adapters</a> · <a href="#kafka-adapter">Kafka adapter</a> · <a href="#install">Install</a> · <a href="#build">Build</a></sub></p>

### Modules

<table>
  <thead>
    <tr>
      <th align="left" width="240">Module</th>
      <th align="left">Use when</th>
      <th align="left">External dependency</th>
    </tr>
  </thead>
  <tbody>
    <tr><td nowrap>freeway-http-jetty</td><td>Jetty 12 deployments, Servlet-style processing or existing Jetty tooling</td><td><a href="https://jetty.org">Jetty</a> 12.1.13</td></tr>
    <tr><td nowrap>freeway-http-undertow</td><td>Undertow-specific handler/listener configuration or existing Undertow tooling</td><td><a href="https://undertow.io">Undertow</a> 2.4.3.Final</td></tr>
    <tr><td nowrap>freeway-mq-kafka</td><td>Distributed event streaming across services</td><td><a href="https://kafka.apache.org">Kafka Clients</a> 4.3.1</td></tr>
    <tr><td nowrap>freeway-fleet-protocol</td><td>Fleet wire contract, headers and shared keys</td><td>none</td></tr>
    <tr><td nowrap>freeway-fleet</td><td>Fleet client, discovery and registrar</td><td>none — JDK <code>HttpClient</code> only</td></tr>
    <tr><td nowrap>freeway-fleet-server</td><td>Standalone Fleet registry server with an H2 <code>LeaseStore</code></td><td><a href="https://h2database.com">H2</a> 2.5.250</td></tr>
    <tr><td nowrap>freeway-cloud-consul</td><td>Service discovery/registration backed by Consul (non-Kubernetes deployments)</td><td>none — JDK <code>HttpClient</code> only</td></tr>
    <tr><td nowrap>freeway-db-hikari</td><td>Connection pooling tuned for high-concurrency OLTP</td><td><a href="https://github.com/brettwooldridge/HikariCP">HikariCP</a> 7.1.0</td></tr>
    <tr><td nowrap>freeway-benchmark</td><td>JMH comparison suite and the <code>bench</code> CLI (not published)</td><td><a href="https://github.com/openjdk/jmh">JMH</a> 1.37; <a href="https://github.com/robaho/httpserver">robaho</a> 1.0.29 <sub>(runtime)</sub></td></tr>
    <tr><td nowrap>freeway-http-adapter-testkit</td><td>Shared fixtures and contracts every engine must pass (test scope only)</td><td>JUnit 6.1.3</td></tr>
    <tr><td nowrap>freeway-logging-testkit</td><td>Cross-JVM proof of Logback takeover, JUL isolation and default exclusion (test scope only, not published)</td><td><a href="https://logback.qos.ch">Logback</a> 1.5.38</td></tr>
  </tbody>
</table>

HTTP/DB adapters are leaf nodes. Fleet is a first-party client/server pair
(`server -> protocol`, with the client in server tests only); `freeway-benchmark`
intentionally depends on both engines and is excluded from deployment. Its
robaho dependency is runtime-only: it supplies the `robaho-native` comparison
engine through the JDK `HttpServer` SPI and is absent from the benchmark compile
classpath.

### HTTP adapters

Both adapters receive the same `HttpServerConfig` as the built-in engine and
honor the shared `freeway.http.server.*` keys where the underlying server
supports them. The Jetty and Undertow adapters also read the shared
`freeway.http.ssl.*` keys with the same names and defaults as the built-in
engine, so a TLS configuration written for `FreewayHttpEngine` works unchanged
on either adapter.

All keys below resolve through the full config cascade (CLI, JVM properties, env,
files) — `-D` flags in the example are just one tier. A malformed
`max-frame-size` fails startup naming the key instead of silently falling back.

#### Configuration

<details>
<summary><strong>TLS and HTTP/2 keys</strong></summary>

<sub>

| Property | Default | Applies to | Meaning |
|---|---|---|---|
| `freeway.http.ssl.enabled` | `false` | Jetty, Undertow | Serve HTTPS instead of plain HTTP. |
| `freeway.http.ssl.key-store` | — | Jetty, Undertow | Path to the key store (PKCS12 or JKS). |
| `freeway.http.ssl.key-store-password` | `` | Jetty, Undertow | Key store password. |
| `freeway.http.ssl.key-store-type` | inferred (`.jks` → JKS, else PKCS12) | Jetty, Undertow | Key store type. |
| `freeway.http.ssl.trust-store` | — | Jetty, Undertow | Optional trust store for validating peer certificates. |
| `freeway.http.ssl.trust-store-password` | `` | Jetty, Undertow | Trust store password. |
| `freeway.http.ssl.trust-store-type` | `PKCS12` | Jetty, Undertow | Trust store type. |
| `freeway.http.ssl.client-auth` | `false` | Jetty, Undertow | Require client certificates (mTLS). |
| `freeway.http.ssl.protocols` | (JVM defaults) | Jetty, Undertow | Comma-separated TLS protocol versions (e.g. `TLSv1.3,TLSv1.2`). |
| `freeway.http.ssl.ciphers` | (JVM defaults) | Jetty, Undertow | Comma-separated TLS cipher suite names. |
| `freeway.http.ssl.http2` | `true` | Jetty, Undertow | HTTP/2 over TLS via ALPN (same key and default as the built-in engine). |
| `freeway.http.ssl.key-password` | (same as store) | Jetty | Key manager password (Jetty extension for JKS keystores). |
| `freeway.http.ssl.key-alias` | (first entry) | Jetty | Alias of the server certificate (Jetty extension). |
| `freeway.http.http2` | `false` | Jetty | Jetty-only h2c (cleartext HTTP/2). Ignored when TLS is enabled — use `freeway.http.ssl.http2` there. |
| `freeway.http.websocket.max-frame-size` | `65536` | Jetty, Undertow | Maximum WebSocket text/binary message size in bytes; `0` disables the limit. |
| `freeway.http.undertow.dispatch-io` | `true` | Undertow | Dispatch handler execution from I/O threads to the worker pool. Keep enabled when handlers can block; set `false` only for fully non-blocking handlers. |
| `freeway.http.jetty.dispatch-io` | `true` | Jetty | Dispatch handler execution from the connection's producer thread to the Jetty thread pool. Keep enabled when handlers can block; `false` runs inline on the producer and skips the per-request hand-off. A blocking handler in this mode stalls the shared producer thread. |

</sub>

</details>

```bash
-Dfreeway.http.ssl.enabled=true \
-Dfreeway.http.ssl.key-store=/etc/freeway/keystore.p12 \
-Dfreeway.http.ssl.key-store-password=changeit \
-Dfreeway.http.ssl.http2=true
```

<details>
<summary><strong>Shared server settings</strong></summary>

<sub>

| Key | Adapters | Notes |
|---|---|---|
| `freeway.http.server.read-timeout` | Jetty, Undertow | Jetty maps it to the connector idle timeout; Undertow to its idle and request-parse timeouts (default 30s, `0` disables). |
| `freeway.http.server.backlog` | Jetty, Undertow | Accept backlog (0 = platform default). |
| `freeway.http.server.max-connections` | Jetty | Rejected at accept time via Jetty's connection limit. Undertow has no equivalent; the knob is ignored there. |
| `freeway.http.server.write-timeout` | — | Neither adapter exposes a per-write timeout; the knob is ignored. |
| `freeway.http.server.receive-buffer-size` / `send-buffer-size` | Jetty, Undertow | Desired socket buffer sizes (0 = OS default). |
| `freeway.http.compression.enabled` / `min-size` | Jetty, Undertow | gzip response compression with the built-in engine's semantics (status, min-size, `Accept-Encoding` and Content-Type gates, `Vary: Accept-Encoding`). |

</sub>

</details>

#### WebSocket notes

Both adapters' WebSocket transports send frames asynchronously, so application
code can safely call `sendText` / `sendBinary` / `ping` from the server's receive
(I/O) threads without deadlocking. As a trade-off, send failures are logged by
the adapter instead of being thrown to the caller, and `close()` initiates the
graceful close handshake and returns immediately. If you need to react to send
failures, watch the adapter's error logs or the underlying server's WebSocket
error callbacks.

`ctx.sse()` streams are owned by the application: close the returned emitter
when finished so the underlying connection can be released.

WebSocket sends have no backpressure: a slow or disconnected client accumulates
outbound frames until the server's buffers are exhausted, so applications that
broadcast to many clients should bound their own fan-out (e.g. drop or throttle
sessions that fall behind). Send failures are logged by the adapters.

On the Undertow adapter, WebSocket messages over
`freeway.http.websocket.max-frame-size` are rejected with a 1009 close frame
after Undertow buffers them; the cap prevents the message from reaching
application code and closes the connection. Jetty rejects oversized messages
during assembly. On Undertow an oversized message surfaces to the application as
`onError` only (no `onClose` callback); on Jetty the listener also receives
`onClose(1009)`.

The Undertow adapter's idle/parse deadlines come from the shared
`freeway.http.server.read-timeout` (default 30 seconds, matching the built-in
engine; `0` disables) — connections with no traffic are dropped. Long-lived SSE
streams should therefore emit periodic heartbeat comments, and WebSocket peers
should stay within the idle window or expect the connection to be closed.

#### Engine selection

`freeway-http-undertow` and `freeway-http-jetty` are independent adapters: an
application depends on exactly **one** of them, so the other's `HttpEngine`
binding is not on the classpath at all. No conflict occurs, even with SPI
auto-discovery enabled.

Both adapters register their engine as `HttpEngine.primary()`. This only matters
when both artifacts end up on the same classpath (for example
`freeway-benchmark`, which bundles them for comparative runs). In that case,
either disable auto-discovery (`FreewayApp.create(...).autoDiscovery(false)`) and
install the desired module explicitly, or resolve the engine by id.

### Kafka adapter

#### Security model

The receiving plane deserializes a record only into the payload type a
`KafkaEvents.subscribe(prefix, type, handler)` declared — the subscription table
is the inbound allowlist. A record whose topic matches no subscription is
acknowledged and skipped without being read at all, so an undeclared class can
never be loaded off the wire. The former `freeway.kafka.allowed-event-types` key
is gone: declaring a subscription is the only way to receive, and there is no
"silent empty allowlist" state to misconfigure.

This replaces the old class-name allowlist. Consumers that bridged typed events
now declare the type per subscription instead:

```java
events.subscribe("orders", OrderCreated.class, handler);
```

Rejected (undecodable) messages follow the poison-message policy: they are
retried, then either logged and moved on (default
`freeway.kafka.poison-policy=skip`) or fail the subscriber without committing
(`fail`). A throwing *handler* is never poison — it is isolated and counted,
because the record itself decoded fine.

With `fail`, the failing offset is not committed, so already-processed events
from the same batch may be redelivered after a restart (at-least-once
semantics). Consumers that need exactly-once deduplicate by their own business
key — the `send` partition key is the natural one. Transports without a natural
business key (signal events, handlers not written idempotent) can instead arm the
bounded redelivery window — `freeway.kafka.dedup-capacity` (default `0`, off)
remembers the last N dispatch ids and drops second copies before dispatch.
Poison still throws first, so the DLQ path is unaffected; unmatched records never
occupy the window.

#### Delivery settings

<details>
<summary><strong>Delivery and poison-message keys</strong></summary>

<sub>

| Property | Default | Meaning |
|---|---|---|
| `freeway.kafka.max-retries` | `1` | Number of delivery/processing retries before a message is treated as poison. |
| `freeway.kafka.retry-backoff-ms` | `1000` | Base backoff between retries; each attempt doubles it (exponential). |
| `freeway.kafka.dlq-topic` | (unset) | When set, poison messages are published to this dead-letter topic instead of being skipped. The original topic/offset and a reason are preserved in `X-DLQ-Original-Topic` / `X-DLQ-Original-Offset` / `X-DLQ-Reason` headers. |
| `freeway.kafka.concurrency` | `1` | Number of poll/processing workers; when > 1 messages are fanned out by key so ordering per key is preserved. |
| `freeway.kafka.dedup-capacity` | `0` (off) | Bound on remembered dispatch ids for redelivery suppression (rebalance/restart replays carry the same CE id); second copies are dropped before dispatch. Poison still throws first, so the DLQ path is unaffected. |

</sub>

</details>

**Plane topology.** `KafkaEvents.send(topic, payload)` writes to the named
topic itself — the routing topic and the wire topic are one. `freeway.kafka.topics`
is the subscriber's poll set: a topic nobody polls receives nothing, and
`subscribe` warns at registration when its prefix can match no polled topic.

`send` never throws at the caller: synchronous producer failures are logged and
counted, and a null payload is skipped (in Kafka a null value is a compaction
tombstone, not an event). The plane is started and closed by the
`freeway.kafka.lifecycle` hook (matching core's `freeway.<module>.<thing>`
naming); `send` works before the hook too (the producer is live from
construction — only the poll loop waits for lifecycle).

Additional Kafka client options (TLS, SASL, etc.) can be passed through with
`freeway.kafka.properties` as semicolon-separated `key=value` pairs, e.g.
`-Dfreeway.kafka.properties=security.protocol=SASL_SSL;sasl.mechanism=PLAIN`.
These are applied last and override adapter defaults.

Records sent by `KafkaEvents` carry the optional `send` partition key as the
Kafka record key (per-aggregate ordering on the broker, per-key parallel
consumption), and the same key rides the `ce-subject` header. Sends without a key
have a null record key — no cross-broker ordering promise. Keys set by other
producers are honored.

Without a DLQ topic, poison messages follow `freeway.kafka.poison-policy` as
described above. With a DLQ topic, they are moved to the DLQ first and the policy
then decides whether processing continues (`skip`) or stops (`fail`). If the DLQ
write itself fails, the subscriber stops without committing so the message is
redelivered until the DLQ accepts it — a poison message is never silently
dropped.

### Install

All extension modules track the Freeway core version. Use `2.0.0` for the
current release:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-http-undertow</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Build

Requires JDK 25+. Build Freeway core first, then this repository:

```bash
# 1. From a Freeway core checkout
mvn -DskipTests install

# 2. From this checkout
mvn test

# 3. Single module
mvn -pl freeway-http-undertow -am test

# 4. Format check (bound to verify, not part of mvn test)
mvn spotless:check
```

CI runs `mvn -B -ntp verify -Dgpg.skip=true`.

### Documentation

- [Core documentation](https://github.com/dzb/freeway/tree/main/docs)
- [Extension documentation index](docs/README.md)
- [Fleet client](docs/modules/fleet.md) · [Fleet server](docs/modules/fleet-server.md)
- [Consul adapter design](docs/design/consul-adapter.md)
- [Release notes](docs/releases/) · [Changelog](CHANGELOG.md)

<p align="center"><sub>Licensed under <a href="LICENSE">Apache 2.0</a>.</sub></p>
