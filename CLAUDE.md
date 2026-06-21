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
```

## Module Dependency Graph

```
freeway-ext (parent, inherits from freeway-parent)
 ├─ freeway-http-undertow ── freeway-ioc, freeway-http, undertow-core
 ├─ freeway-mq-kafka      ── freeway-ioc, freeway-commons, kafka-clients
 └─ freeway-db-hikari     ── freeway-ioc, freeway-db, HikariCP
```

All modules are leaf nodes — no cross-dependencies between extension modules.
Each depends only on Freeway core modules and its specific third-party library.

## Architecture

- Every adapter module implements a core SPI (`HttpEngine`, `Pool`, `EventBridge`).
- Modules are selected via config or explicit binding; nothing is auto-discovered.
- The `freeway-ext` parent POM inherits from `freeway-parent` for build plugins
  (compiler 25, surefire, source, javadoc, GPG signing, Central publishing).
- Core dependency versions are pinned via `<freeway.version>` in the parent's
  `dependencyManagement`, preventing accidental version drift from the core.

## Naming

- Adapter packages: `com.jujin.freeway.{area}.{implementation}` (e.g. `http.undertow`, `db.hikari`).
- Module artifact names follow the core convention: `freeway-{area}-{impl}`.
