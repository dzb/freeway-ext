# Freeway Benchmarks

This module keeps performance work separate from the normal test suite.
See [BENCHMARK_PROTOCOL.md](BENCHMARK_PROTOCOL.md) for the benchmark rules and reporting format.

## Prerequisites

Install the core reactor into your local Maven repository before running benchmarks:

```bash
mvn -pl freeway-commons,freeway-ioc,freeway-boot,freeway-http,freeway-db -am install -DskipTests "-Dgpg.skip=true"
```

## Quickstart: CLI Benchmark

The CLI (`BenchApp`) is the **primary tool** for day-to-day benchmarking.
Results are persisted to SQLite (`bench.db`) so you can compare runs later.

```bash
# Build benchmark module
mvn -f freeway-benchmark/pom.xml -am -DskipTests process-classes

# Single run
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args='run --engine=freeway --scenario=ping --concurrency=32 --requests=5000 --warmup=500 --runs=3'

# WebSocket echo benchmark
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args='run --engine=freeway --scenario=ws_echo --concurrency=16 --requests=1000 --warmup=200 --runs=3 --mode=ws'

# Matrix sweep: compare engines
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args='suite --engines=freeway,undertow-native --scenarios=ping,json --concurrency=8,16 --requests=2000 --runs=3 --output=report.md'

# View history
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args='list --limit=10'

# Compare two runs (auto-detects baseline, flags regressions >3%)
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args='compare --from=1 --to=3'
```

### CLI contract

- **Exit codes**: `0` success, `1` usage or internal error, `2` a gate failed
  (`compare` reporting regressions). Safe to wire into CI.
- **Usage errors vs crashes**: a bad flag (`--runs=soon`), an unknown engine, or an
  `--output` path whose extension does not match the report (`run` writes JSON, so
  `--output=x.json`; `suite` writes Markdown, so `--output=x.md`) prints one
  `Error: ...` line plus a usage hint. Internal failures keep the stack trace.
- **Reports**: every table — `run`, `suite`, `list`, `history`, `compare` — comes from
  the same Markdown renderer with numeric columns right-aligned, so terminal output
  and written reports look the same. Rates read as `1.20M` / `17.3k`, never `1200000`.

## Forked (Isolated) Benchmark

`BenchFork` runs server and client in **separate JVM processes** — zero
cross-contamination between measured target and measurement harness. One server
JVM stays resident for the whole engine/mode: the client sends `bench.warmup`
warmup requests (the result is discarded), pauses, then measures `bench.runs`
rounds against that same server with a `bench.pauseMillis` pause between rounds,
so every measured round runs against a JIT-warm server. Use this when you need
process-level isolation for final performance claims.

```bash
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.run.BenchFork \
  -Dbench.engine=freeway -Dbench.mode=keepalive \
  -Dbench.requests=20000 -Dbench.concurrency=32 -Dbench.warmup=2000 -Dbench.runs=3
```

Run with `java -cp` for fully isolated classpath (avoids `exec:java` classpath issues):

```bash
mvn -f freeway-benchmark/pom.xml -am process-classes -DskipTests
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     -cp "$(cat freeway-benchmark/target/benchmark.classpath);freeway-benchmark/target/classes" \
     -Dbench.engine=freeway -Dbench.requests=20000 -Dbench.concurrency=32 \
     -Dbench.warmup=2000 -Dbench.runs=3 \
     com.jujin.freeway.bench.run.BenchFork
```

Supported engines:

| Engine             | Description |
|------------------|-------------|
| `freeway`          | Freeway's built-in HTTP engine (`FreewayHttpEngine`) |
| `jdk-native`       | Bare JDK `com.sun.net.httpserver.HttpServer` (baseline) |
| `robaho-native`    | Robaho's `HttpServer` implementation (`robaho.net.httpserver`) |
| `undertow-native`  | Native Undertow server (`io.undertow.Undertow`) with a platform worker pool |
| `undertow-vt`      | Native Undertow with a virtual-thread worker pool (XNIO external executor) |
| `undertow-adapter` | Freeway's Undertow adapter (`UndertowWebEngine`) |
| `jetty-native`     | Native Jetty server with a platform `QueuedThreadPool` |
| `jetty-vt`         | Native Jetty with virtual threads for blocking work (`QueuedThreadPool` + virtual-thread executor) |
| `jetty-adapter`    | Freeway's Jetty adapter (`JettyWebEngine`) |

## JMH Microbenchmarks

Run the microbenchmarks through the JMH launcher. Every benchmark class carries the protocol
defaults from [BENCHMARK_PROTOCOL.md](BENCHMARK_PROTOCOL.md) §3 as annotations (2 forks, 5×1s
warmup, 5×1s measurement, `thrpt`), so a plain invocation is already protocol-conformant:

```bash
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=org.openjdk.jmh.Main \
  -Dexec.args='com.jujin.freeway.bench.jmh.RouteIndexBenchmark'
```

To persist microbenchmark scores in the same tables as the HTTP runs (`list`, `history`,
`compare` then see them), use the CLI command instead of the JMH launcher:

```bash
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args='jmh --include=com.jujin.freeway.bench.jmh.RouteIndexBenchmark'
```

`--forks/--warmup/--iterations/--time` mirror the JMH options (the annotated defaults apply when
they are omitted), and the JMH parameter block is recorded on the run row so a later comparison can
tell a protocol-conforming run from a local-iteration one.

For quick local iteration, override the annotations explicitly — `-f 0 -wi 1 -i 1` runs in the
host VM with one second of each phase. Those numbers are reference only, never decision-grade:
the protocol requires the annotated defaults, and a report must record the options it used.

Useful benchmark classes:

- `com.jujin.freeway.bench.jmh.JsonCodecBenchmark` — JSON serialization/deserialization
- `com.jujin.freeway.bench.jmh.RouteIndexBenchmark` — route matching (exact, param, wildcard)
- `com.jujin.freeway.bench.jmh.MultipartFormBenchmark` — multipart form parsing
- `com.jujin.freeway.http.engine.Http1xParserBenchmark` — HTTP/1.1 request parsing
- `com.jujin.freeway.http.engine.HttpContextOutputBenchmark` — response output (text, JSON, not-found)
- `com.jujin.freeway.http.engine.HttpContextLookupBenchmark` — header/query/param lookup
- `com.jujin.freeway.http.engine.FilterChainBenchmark` — full filter chain (cors → health)
- `com.jujin.freeway.http.engine.ws.WebSocketFrameBenchmark` — WebSocket frame read/write/construct

The last five deliberately live in the core `com.jujin.freeway.http.*` packages: they measure the
real engine internals (`HttpContextImpl.reset`, `Http1xParser`, `WebSocketFrame.read/write`) and
those are package-private, so a white-box package is the only way to reach them. Every other
benchmark class belongs to `com.jujin.freeway.bench.*` — nothing in this module owns a
`com.jujin.freeway.http` package.

The microbenchmarks are the decision-grade inputs. The HTTP black-box benchmarks
are for local validation and release gating, not for final performance claims.

Forked JMH runs (`-f > 0`, the default) re-launch the JVM from the benchmark
classpath, so the module writes `target/benchmark.classpath` during
`process-classes`; run `mvn -f freeway-benchmark/pom.xml -am process-classes`
first if the forks fail to start.

## ServerHarness API

The `ServerHarness` supports pluggable engines and scenarios:

```java
// Start a server
try (var h = ServerHarness.start(Engine.FREEWAY, Scenario.PING)) {
    int port = h.port();
    // ... send requests ...
}
```

| Scenario   | Method | Path | Response |
|------------|--------|------|----------|
| `PING`     | GET    | `/ping` | 200 "pong" text/plain |
| `JSON`     | GET    | `/api/resource` | 200 `{"id":1,"name":"test"}` |
| `ECHO_BODY`| POST   | `/echo` | 200 + request body echo |
| `WS_ECHO`  | GET    | `/ws/echo` | WebSocket echo (FREEWAY + UNDERTOW only) |

To add a new scenario, add a case in `ServerHarness.freewayRoutes()`,
`bareHandler()`, and `undertowHandler()`.
