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

## SPI module selection

Both `freeway-http-undertow` and `freeway-http-jetty` register their engine as
`HttpEngine.primary()`. Depend on **one** of them in an application, or disable
SPI auto-discovery (`FreewayApp.of(...).autoDiscovery(false)`) and install the
desired module explicitly. `freeway-benchmark` intentionally bundles both for
comparative runs and therefore always disables auto-discovery.

## WebSocket adapter note

`UndertowWebSocketSession` and `JettyWebSocketSession` send frames asynchronously
so application code can safely call `sendText` / `sendBinary` / `ping` from the
server's receive (I/O) threads without deadlocking. As a trade-off, send
failures are logged by the adapter instead of being thrown to the caller, and
`close()` initiates the graceful close handshake and returns immediately. If you
need to react to send failures, watch the adapter's error logs or the underlying
server's WebSocket error callbacks.

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
