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
| `freeway-http-undertow` | Servlet API, Undertow-specific handler/listener config, or existing Undertow operational tooling | [Undertow](https://undertow.io) 2.3.24 |
| `freeway-mq-kafka` | Distributed event streaming across services | [Kafka Clients](https://kafka.apache.org) 3.9 |
| `freeway-db-hikari` | Connection pooling tuned for high-concurrency OLTP | [HikariCP](https://github.com/brettwooldridge/HikariCP) 6.2.1 |
| `freeway-benchmark` | JMH-based micro-benchmarks for HTTP, WebSocket, and DB adapters | [JMH](https://github.com/openjdk/jmh) 1.37 |

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
