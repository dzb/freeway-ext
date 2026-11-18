## Freeway Ext

[![Core](https://img.shields.io/badge/core-2.0.1--SNAPSHOT-blue)](https://github.com/dzb/freeway) [![JDK](https://img.shields.io/badge/JDK-25%2B-orange)](https://jdk.java.net/25/) [![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Adapters and integrations for the [Freeway](https://github.com/dzb/freeway)
framework, built against the core version pinned in `<freeway.version>`
(currently `2.0.1-SNAPSHOT`).

**Latest release: 2.0.0** — [release notes](docs/releases/release-notes-2.0.0.md)

[Documentation](docs/README.md) · [Modules](#modules) · [HTTP adapters](#http-adapters) · [Kafka adapter](#kafka-adapter) · [Build](#build)

---

Freeway core already ships a zero-dependency built-in engine: raw sockets on
virtual threads, HTTP/1.1 keep-alive, HTTP/2 h2c/h2, WebSocket and HTTPS. The
modules here are opt-in: use one only when an application needs a particular HTTP
runtime, broker, registry or database pool.

### Modules

<table>
  <thead>
    <tr>
      <th align="left" width="260" nowrap>Module</th>
      <th align="left" nowrap>Use when</th>
      <th align="left" nowrap>Dependency</th>
    </tr>
  </thead>
  <tbody>
    <tr><td nowrap>freeway-http-jetty</td><td>Jetty 12 deployments, Servlet-style processing or existing Jetty tooling</td><td><a href="https://jetty.org">Jetty</a> 12.1.13</td></tr>
    <tr><td nowrap>freeway-http-undertow</td><td>Undertow-specific handler/listener configuration or existing Undertow tooling</td><td><a href="https://undertow.io">Undertow</a> 2.4.3.Final</td></tr>
    <tr><td nowrap>freeway-mq-kafka</td><td>Distributed event streaming across services</td><td><a href="https://kafka.apache.org">Kafka Clients</a> 4.3.1</td></tr>
    <tr><td nowrap>freeway-fleet-protocol</td><td>Fleet wire contract, headers and shared keys</td><td>none</td></tr>
    <tr><td nowrap>freeway-cloud-fleet</td><td>Fleet client, discovery and registry adapter</td><td>none — JDK HttpClient only</td></tr>
    <tr><td nowrap>freeway-fleet-server</td><td>Standalone executable Fleet Server application with embedded H2 and dashboard</td><td><a href="https://h2database.com">H2</a> 2.5.250</td></tr>
    <tr><td nowrap>freeway-cloud-consul</td><td>Service discovery and membership backed by Consul (non-Kubernetes deployments)</td><td>none — JDK HttpClient only</td></tr>
    <tr><td nowrap>freeway-cloud-k8s</td><td>Read-only Kubernetes EndpointSlice discovery adapter</td><td><a href="https://github.com/fabric8io/kubernetes-client">Fabric8 Kubernetes Client</a> 8.0.0</td></tr>
    <tr><td nowrap>freeway-db-hikari</td><td>Connection pooling tuned for high-concurrency OLTP</td><td><a href="https://github.com/brettwooldridge/HikariCP">HikariCP</a> 7.1.0</td></tr>
    <tr><td nowrap>freeway-benchmark</td><td>JMH comparison suite and the bench CLI (not published)</td><td><a href="https://github.com/openjdk/jmh">JMH</a> 1.37; <a href="https://github.com/robaho/httpserver">robaho</a> 1.0.29 (runtime)</td></tr>
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

### HTTP Adapters

Both adapters receive the same `HttpServerConfig` as the built-in engine and honor
the shared `freeway.http.server.*` keys where the underlying server supports
them. They also read the shared `freeway.http.ssl.*` keys with the same names and
defaults as the built-in engine, so a TLS configuration written for
`FreewayHttpEngine` works unchanged on either adapter.

All keys resolve through the full config cascade (CLI, JVM properties, env and
files). A malformed `max-frame-size` fails startup naming the key instead of
silently falling back.

#### Configuration

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
| `freeway.http.ssl.protocols` | JVM defaults | Jetty, Undertow | Comma-separated TLS protocol versions, for example `TLSv1.3,TLSv1.2`. |
| `freeway.http.ssl.ciphers` | JVM defaults | Jetty, Undertow | Comma-separated TLS cipher suite names. |
| `freeway.http.ssl.http2` | `true` | Jetty, Undertow | HTTP/2 over TLS via ALPN. |
| `freeway.http.ssl.key-password` | same as store | Jetty | Key manager password for JKS keystores. |
| `freeway.http.ssl.key-alias` | first entry | Jetty | Alias of the server certificate. |
| `freeway.http.http2` | `false` | Jetty | Jetty-only h2c. Ignored when TLS is enabled; use `freeway.http.ssl.http2` there. |
| `freeway.http.websocket.max-frame-size` | `65536` | Jetty, Undertow | Maximum WebSocket text/binary message size in bytes; `0` disables the limit. |
| `freeway.http.undertow.dispatch-io` | `true` | Undertow | Dispatch handler execution from I/O threads to the worker pool. Keep enabled when handlers can block. |
| `freeway.http.jetty.dispatch-io` | `true` | Jetty | Dispatch handler execution to the Jetty thread pool. Keep enabled when handlers can block; `false` runs inline on the producer. |

Example for Jetty with TLS and HTTP/2:

```bash
-Dfreeway.http.ssl.enabled=true \
-Dfreeway.http.ssl.key-store=/etc/freeway/keystore.p12 \
-Dfreeway.http.ssl.key-store-password=changeit \
-Dfreeway.http.ssl.http2=true
```

#### Shared Server Settings

| Key | Adapters | Notes |
|---|---|---|
| `freeway.http.server.read-timeout` | Jetty, Undertow | Jetty maps it to the connector idle timeout; Undertow to its idle and request-parse timeouts (default 30s, `0` disables). |
| `freeway.http.server.backlog` | Jetty, Undertow | Accept backlog (0 = platform default). |
| `freeway.http.server.max-connections` | Jetty | Rejected at accept time via Jetty's connection limit. Undertow has no equivalent; the knob is ignored there. |
| `freeway.http.server.write-timeout` | — | Neither adapter exposes a per-write timeout; the knob is ignored. |
| `freeway.http.server.receive-buffer-size` / `send-buffer-size` | Jetty, Undertow | Desired socket buffer sizes (0 = OS default). |
| `freeway.http.compression.enabled` / `min-size` | Jetty, Undertow | gzip response compression with the built-in engine's semantics. |

#### WebSocket Notes

Both adapters' WebSocket transports send frames asynchronously, so application
code can safely call `sendText` / `sendBinary` / `ping` from the server's receive
threads without deadlocking. Send failures are logged by the adapter instead of
being thrown to the caller, and `close()` initiates the graceful close handshake
and returns immediately.

`ctx.sse()` streams are owned by the application: close the returned emitter when
finished so the underlying connection can be released.

WebSocket sends have no backpressure. A slow or disconnected client accumulates
outbound frames until the server's buffers are exhausted, so applications that
broadcast to many clients should bound their own fan-out. Send failures are
logged by the adapters.

On Undertow, messages over `freeway.http.websocket.max-frame-size` are rejected
with a 1009 close frame after Undertow buffers them; the cap prevents the message
from reaching application code and closes the connection. Jetty rejects oversized
messages during assembly. On Undertow an oversized message surfaces to the
application as `onError` only; on Jetty the listener also receives
`onClose(1009)`.

The Undertow adapter's idle/parse deadlines come from the shared
`freeway.http.server.read-timeout` (default 30 seconds, matching the built-in
engine; `0` disables). Connections with no traffic are dropped. Long-lived SSE
streams should emit periodic heartbeat comments, and WebSocket peers should stay
within the idle window.

#### Engine Selection

`freeway-http-undertow` and `freeway-http-jetty` are independent adapters: an
application depends on exactly one of them, so the other's `HttpEngine` binding
is not on the classpath at all. No conflict occurs, even with SPI auto-discovery
enabled.

Both adapters register their engine as `HttpEngine.primary()`. This only matters
when both artifacts end up on the same classpath, for example in
`freeway-benchmark`. In that case, disable auto-discovery
(`FreewayApp.create(...).autoDiscovery(false)`) and install the desired module
explicitly, or resolve the engine by id.

### Kafka Adapter

#### Security Model

The receiving plane deserializes a record only into the payload type a
`KafkaEvents.subscribe(prefix, type, handler)` declared. The subscription table is
the inbound allowlist. A record whose topic matches no subscription is
acknowledged and skipped without being read, so an undeclared class can never be
loaded off the wire.

The former `freeway.kafka.allowed-event-types` key is gone. Declaring a
subscription is the only way to receive:

```java
events.subscribe("orders", OrderCreated.class, handler);
```

Rejected or undecodable messages follow the poison-message policy: they are
retried, then either logged and moved on (default
`freeway.kafka.poison-policy=skip`) or fail the subscriber without committing
(`fail`). A throwing handler is never poison; it is isolated and counted because
the record itself decoded fine.

With `fail`, the failing offset is not committed, so already-processed events
from the same batch may be redelivered after a restart. Consumers that need
exactly-once behavior deduplicate by their own business key; the `send` partition
key is the natural one. Transports without a natural business key can use
`freeway.kafka.dedup-capacity` (default `0`, off), which remembers the last N
dispatch ids and drops second copies before dispatch.

#### Delivery Settings

| Property | Default | Meaning |
|---|---|---|
| `freeway.kafka.max-retries` | `1` | Delivery/processing retries before a message is treated as poison. |
| `freeway.kafka.retry-backoff-ms` | `1000` | Base retry backoff; each attempt doubles it. |
| `freeway.kafka.dlq-topic` | unset | Poison messages are published to this topic instead of being skipped. Original topic/offset and reason are preserved in `X-DLQ-Original-Topic` / `X-DLQ-Original-Offset` / `X-DLQ-Reason`. |
| `freeway.kafka.concurrency` | `1` | Poll/processing workers. When greater than 1, messages are fanned out by key so per-key ordering is preserved. |
| `freeway.kafka.dedup-capacity` | `0` off | Bounds remembered dispatch ids for redelivery suppression. Second copies are dropped before dispatch. |

#### Plane Topology

`KafkaEvents.send(topic, payload)` writes to the named topic itself: the routing
topic and the wire topic are one. `freeway.kafka.topics` is the subscriber's poll
set, so a topic nobody polls receives nothing. `subscribe` warns at registration
when its prefix can match no polled topic.

`send` never throws at the caller: synchronous producer failures are logged and
counted, and a null payload is skipped because a null Kafka value is a compaction
tombstone, not an event. The plane is started and closed by the
`freeway.kafka.lifecycle` hook. `send` works before the hook too; only the poll
loop waits for lifecycle.

Additional Kafka client options such as TLS and SASL can be passed through with
`freeway.kafka.properties` as semicolon-separated `key=value` pairs:

```text
-Dfreeway.kafka.properties=security.protocol=SASL_SSL;sasl.mechanism=PLAIN
```

These properties are applied last and override adapter defaults.

Records sent by `KafkaEvents` carry the optional `send` partition key as the
Kafka record key and as the `ce-subject` header. Sends without a key have a null
record key and no cross-broker ordering promise. Keys set by other producers are
honored.

Without a DLQ topic, poison messages follow `freeway.kafka.poison-policy`. With
a DLQ topic, poison is moved to the DLQ first and the policy decides whether
processing continues (`skip`) or stops (`fail`). If the DLQ write fails, the
subscriber stops without committing so the message is redelivered until the DLQ
accepts it.

### Install

Every module here builds against the core version pinned in `<freeway.version>`.
Maven Central carries the `1.5.7` line: the 2.0 tags exist on GitHub, but no 2.0
artifact has been deployed, so a `2.0.x` coordinate does not resolve yet.

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-http-undertow</artifactId>
    <version>1.5.7</version>
</dependency>
```

### Build

Requires JDK 25+. Build Freeway core first, then this repository:

```bash
# From a Freeway core checkout
mvn -DskipTests install

# From this checkout
mvn test

# Single module
mvn -pl freeway-http-undertow -am test

# Format check
mvn spotless:check
```

CI runs `mvn -B -ntp verify -Dgpg.skip=true`.

### Documentation

- [Core documentation](https://github.com/dzb/freeway/tree/main/docs)
- [Extension documentation index](docs/README.md)
- [Fleet client](docs/modules/fleet.md) and [Fleet server](docs/modules/fleet-server.md)
- [Consul adapter design](docs/design/consul-adapter.md)
- [Release notes](docs/releases/) and [changelog](CHANGELOG.md)

### License

[Apache 2.0](LICENSE)
