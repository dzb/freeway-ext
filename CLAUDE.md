# CLAUDE.md

This file provides guidance to Claude Code when working in this repository.

## Build

Requires JDK 25+. Build Freeway core first, then extensions:

```bash
# 1. Install core modules into local Maven repository
cd ../freeway && mvn install -DskipTests

# 2. Build all extensions
mvn test

# 3. Single module
mvn -pl freeway-http-undertow -am test

# 4. Run benchmarks (fork-based, from project root)
mvn -f freeway-benchmark/pom.xml exec:java \
  -Dexec.mainClass=com.jujin.freeway.benchmarks.BenchFork \
  -Dbench.engine=freeway -Dbench.requests=20000 -Dbench.concurrency=32 -Dbench.runs=3
```

## Module Dependency Graph

```
freeway-ext (parent, inherits from freeway-parent)
 ├─ freeway-http-undertow ── freeway-ioc, freeway-http, undertow-core
 ├─ freeway-mq-kafka      ── freeway-ioc, freeway-commons, kafka-clients
 ├─ freeway-db-hikari     ── freeway-ioc, freeway-db, HikariCP
 └─ freeway-benchmark     ── freeway-http, freeway-boot, freeway-db,
                              freeway-http-undertow, JMH, robaho-httpserver
```

The three adapter modules are leaf nodes — no cross-dependencies between them.
Each depends only on Freeway core modules and its specific third-party library.

`freeway-benchmark` is the exception: it depends on `freeway-http-undertow` (and
third-party engines) to run comparative benchmarks. It is not published
(`maven.deploy.skip=true`).

## Architecture

- Every adapter module implements a core SPI (`HttpEngine`, `Pool`, `EventBridge`).
- Modules are selected via config or explicit binding; nothing is auto-discovered.
- The `freeway-ext` parent POM inherits from `freeway-parent` for build plugins
  (compiler 25, surefire, source, javadoc, GPG signing, Central publishing).
- Core dependency versions are pinned via `<freeway.version>` in the parent's
  `dependencyManagement`, preventing accidental version drift from the core.
- `freeway-benchmark` is not an adapter — it is a JMH-based performance test suite
  that compares the Undertow adapter against other engines. It is the only module
  with cross-extension dependencies and is excluded from deployment.

## Naming

- Adapter packages: `com.jujin.freeway.{area}.{implementation}` (e.g. `http.undertow`, `db.hikari`).
- Module artifact names follow the core convention: `freeway-{area}-{impl}`.
