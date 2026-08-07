# freeway-ext

Optional third-party integrations for the [Freeway](https://github.com/dzb/freeway) framework.

## Built-in engine

Freeway core ships with **`FreewayHttpEngine`** — a raw-socket engine built from scratch
on virtual threads (one per connection).  It supports:

- HTTP/1.1 with keep-alive and per-connection context reuse
- HTTP/2 — both h2c (cleartext upgrade via `PRI * HTTP/2.0` preface) and h2 (TLS with ALPN negotiation)
- WebSocket — full RFC 6455 implementation (text, binary, ping/pong, close handshake, fragmentation)
- HTTPS — TLS wrapping with ALPN for protocol selection

Internally it uses a bulk-read parser with reusable 4 KB buffers, assembles the entire
HTTP response into a single buffer for one-shot socket writes, and reuses parser and
context objects across keep-alive requests on the same connection.  Zero third-party
dependencies.

For the vast majority of applications, this is all you need.

## When to use an extension module

| Module | When to use | External Dependency |
|--------|-------------|-------------------|
| `freeway-http-undertow` | Undertow-specific handler/listener config, or existing Undertow operational tooling | [Undertow](https://undertow.io) 2.4.2.Final |
| `freeway-http-jetty` | Jetty 12 deployments, Servlet-style processing, or existing Jetty operational tooling | [Jetty](https://jetty.org) 12.1.11 |
| `freeway-mq-kafka` | Distributed event streaming across services | [Kafka Clients](https://kafka.apache.org) 4.3.1 |
| `freeway-db-hikari` | Connection pooling tuned for high-concurrency OLTP | [HikariCP](https://github.com/brettwooldridge/HikariCP) 7.1.0 |
| `freeway-benchmark` | JMH-based micro-benchmarks for HTTP, WebSocket, and DB adapters | [JMH](https://github.com/openjdk/jmh) 1.37 |

## Kafka security note

The Kafka subscriber only deserializes messages whose `X-Event-Type` header is on
the `freeway.kafka.allowed-event-types` allowlist (comma-separated class names).
Messages without the header are treated as plain JSON `Map`. If the allowlist is
empty (the default), **typed messages are rejected** instead of being
deserialized into arbitrary classes from the classpath.

> ⚠️ This is a breaking change from earlier versions. Existing consumers that
> rely on typed events must configure the allowlist, for example:
>
> ```
> -Dfreeway.kafka.allowed-event-types=com.acme.OrderCreated,com.acme.PaymentReceived
> ```
>
> Rejected messages follow the poison-message policy: they are retried once,
> then either logged and skipped (default `freeway.kafka.poison-policy=skip`) or
> fail the subscriber without committing (`fail`).
>
> With `fail`, the failing offset is not committed, so already-published events
> from the same batch may be redelivered after a restart (at-least-once
> semantics).

Additional Kafka client options (TLS, SASL, etc.) can be passed through with
`freeway.kafka.properties` as semicolon-separated `key=value` pairs, e.g.
`-Dfreeway.kafka.properties=security.protocol=SASL_SSL;sasl.mechanism=PLAIN`.
These are applied last and override adapter defaults.

### Kafka delivery settings

| Property | Default | Meaning |
|----------|---------|---------|
| `freeway.kafka.max-retries` | `1` | Number of delivery/processing retries before a message is treated as poison. |
| `freeway.kafka.retry-backoff-ms` | `1000` | Base backoff between retries; each attempt doubles it (exponential). |
| `freeway.kafka.dlq-topic` | (unset) | When set, poison messages are published to this dead-letter topic instead of being skipped. The original topic/offset and a reason are preserved in `X-DLQ-Original-Topic` / `X-DLQ-Original-Offset` / `X-DLQ-Reason` headers. |
| `freeway.kafka.concurrency` | `1` | Number of poll/processing workers; when > 1 messages are fanned out by key so ordering per key is preserved. |

Messages published by `KafkaEventBridge` carry a null key, so keyed fan-out
does not apply to them: self-produced events are always processed by the same
worker (in order). Keys set by other producers are honored.

Without a DLQ topic, poison messages follow `freeway.kafka.poison-policy` as
described above. With a DLQ topic, they are moved to the DLQ first and the
policy then decides whether processing continues (`skip`) or stops (`fail`).
If the DLQ write itself fails, the subscriber stops without committing so the
message is redelivered until the DLQ accepts it — a poison message is never
silently dropped.

## HTTP adapter configuration

The Jetty and Undertow adapters read the following system properties:

| Property | Default | Applies to | Meaning |
|----------|---------|------------|---------|
| `freeway.http.ssl.enabled` | `false` | Jetty, Undertow | Serve HTTPS instead of plain HTTP. |
| `freeway.http.ssl.key-store` | — | Jetty, Undertow | Path to the key store (JKS or PKCS12; Undertow infers the type from the `.jks` extension). |
| `freeway.http.ssl.key-store-password` | `` | Jetty, Undertow | Key store password. |
| `freeway.http.ssl.key-password` | (same as store) | Jetty | Key manager password. |
| `freeway.http.ssl.key-alias` | (first entry) | Jetty | Alias of the server certificate. |
| `freeway.http.http2` | `false` | Jetty | Enable HTTP/2: h2 via ALPN when TLS is enabled, otherwise h2c (cleartext). |
| `freeway.http.websocket.max-frame-size` | `65536` | Jetty, Undertow | Maximum WebSocket text/binary message size in bytes; `0` disables the limit. |
| `freeway.http.undertow.dispatch-io` | `true` | Undertow | Dispatch handler execution from I/O threads to the worker pool. Keep enabled when handlers can block (body reads, DB calls); set `false` only for fully non-blocking handlers. |

Example (Jetty, TLS + HTTP/2):

```bash
-Dfreeway.http.ssl.enabled=true \
-Dfreeway.http.ssl.key-store=/etc/freeway/keystore.p12 \
-Dfreeway.http.ssl.key-store-password=changeit \
-Dfreeway.http.http2=true
```

## Engine modules are independent

`freeway-http-undertow` and `freeway-http-jetty` are independent adapters: an
application depends on exactly **one** of them, so the other's `HttpEngine`
binding is not on the classpath at all. No conflict occurs, even with SPI
auto-discovery enabled.

Both adapters register their engine as `HttpEngine.primary()`. This only
matters when both artifacts end up on the same classpath (for example
`freeway-benchmark`, which bundles them for comparative runs). In that case,
either disable auto-discovery (`FreewayApp.of(...).autoDiscovery(false)`) and
install the desired module explicitly, or resolve the engine by id.

## WebSocket adapter note

`UndertowWebSocketSession` and `JettyWebSocketSession` send frames asynchronously
so application code can safely call `sendText` / `sendBinary` / `ping` from the
server's receive (I/O) threads without deadlocking. As a trade-off, send
failures are logged by the adapter instead of being thrown to the caller, and
`close()` initiates the graceful close handshake and returns immediately. If you
need to react to send failures, watch the adapter's error logs or the underlying
server's WebSocket error callbacks.

`HttpContext.sse()` streams are owned by the application: close the returned
emitter when finished so the underlying connection can be released.

WebSocket sends have no backpressure: a slow or disconnected client accumulates
outbound frames until the server's buffers are exhausted, so applications that
broadcast to many clients should bound their own fan-out (e.g. drop or throttle
sessions that fall behind). Send failures are logged by the adapters.

On the Undertow adapter, WebSocket messages over
`freeway.http.websocket.max-frame-size` are rejected with a 1009 close frame
after Undertow buffers them; the cap prevents the message from reaching
application code and closes the connection. Jetty rejects oversized messages
during assembly. Note: on Undertow an oversized message surfaces to the
application as `onError` only (no `onClose` callback); on Jetty the listener
also receives `onClose(1009)`.

The Undertow adapter sets a 60-second connection idle timeout (and a 30-second
request-parse timeout) — connections with no traffic are dropped. Long-lived
SSE streams should therefore emit periodic heartbeat comments, and WebSocket
peers should stay within the idle window or expect the connection to be
closed.

## Install

Add the Maven Central snapshot repository, then pick the modules you need:

```xml
<dependency>
    <groupId>com.jujin8.freeway</groupId>
    <artifactId>freeway-http-undertow</artifactId>
    <version>${freeway.version}</version>
</dependency>
```

Versions track the Freeway core framework for guaranteed compatibility.

## Build

Requires JDK 25+. Build Freeway core first, then extensions:

```bash
# 1. Install core modules into local Maven repository
cd ../freeway && mvn install -DskipTests

# 2. Build all extensions
cd - && mvn test

# 3. Single module
mvn -pl freeway-http-undertow -am test
```
