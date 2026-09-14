# Repository Guidelines

`freeway-ext` is the JDK 25+ Maven repository holding Freeway's third-party
integrations: the HTTP engine adapters, the message-queue and connection-pool
adapters, the shared adapter testkit, and the benchmark suite. It consumes Freeway
core as a dependency — core never depends on it. Keep changes scoped, explicit, and
convention over configuration.

This file is the single home for ext-repo conventions: build, module layout, naming,
design rules, testing, commit rules. Framework-wide conventions live in the core
repository — [AGENTS.md](https://github.com/dzb/freeway/blob/main/AGENTS.md) and
[docs/ARCHITECTURE.md](https://github.com/dzb/freeway/blob/main/docs/ARCHITECTURE.md)
— and apply here unchanged.

## Build

Requires JDK 25+. Adapters compile against the **installed** core snapshot pinned by
`<freeway.version>`, so install core before building here, and reinstall it after every
core change — a stale snapshot hides ABI changes and surfaces them later as a
`NoSuchMethodError`:

```bash
cd ../freeway && mvn -DskipTests install     # core first
cd ../freeway-ext

mvn test                                     # every module
mvn -pl freeway-http-undertow -am test       # one adapter + its upstream modules
mvn -pl freeway-http-adapter-testkit,freeway-http-jetty test   # the testkit is not installed
mvn spotless:check                           # format gate — not part of `mvn test`
```

- **The format gate is not in the test lifecycle**: `spotless:check` is bound to
  `verify` (google-java-format, which also removes unused imports), so run it explicitly
  before committing.
- **CI is `mvn -B -ntp verify -Dgpg.skip=true`.** That command resolves the GPG plugin
  even when signing is skipped, so it is not the offline loop; use `mvn -o test` plus
  `mvn -o spotless:check` when there is no network.
- **Never run two builds over the same modules at once** — they share `target/`, and an
  incremental compile keeps the previous class, which turns an ABI break green.

## Module Map

| Module | Purpose | Module dependencies |
|--------|---------|---------------------|
| `freeway-http-adapter-testkit` | Shared fixtures (`EngineFixture`, `Pipelines`) and the contracts every engine must pass | core http + commons + cloud; JUnit at compile scope (the contracts *are* the API) |
| `freeway-http-jetty` | Jetty 12 engine adapter (`HttpEngine`) | core ioc + http + commons; jetty-server, jetty-websocket-jetty-server, jetty-http2-server, jetty-alpn-*-server |
| `freeway-http-undertow` | Undertow engine adapter (`HttpEngine`) | core ioc + http + commons; undertow-core |
| `freeway-mq-kafka` | Kafka `EventSink` plus the subscriber that bridges the event bus over a topic | core ioc + commons; kafka-clients |
| `freeway-db-hikari` | HikariCP pool adapter (`Pool`) | core ioc + db; HikariCP |
| `freeway-benchmark` | JMH comparison suite and the `bench` CLI (not published) | core http + boot + db; both engine adapters; JMH, robaho-httpserver, sqlite-jdbc |

The adapter modules are leaf nodes: no cross-dependencies between them, and each depends
only on core modules plus its own third-party library. `freeway-benchmark` is the one
exception — comparing engines requires both of them — and it is excluded from deployment
(`skipPublishing`, plus `maven.deploy.skip` for the plain deploy lifecycle).

## Naming

- Adapter packages: `com.jujin.freeway.{area}.{implementation}` (`http.jetty`,
  `db.hikari`).
- Artifact names follow the core convention: `freeway-{area}-{impl}`.
- Shared test API lives in `freeway-http-adapter-testkit`, package
  `com.jujin.freeway.http.testkit`.

## Design Rules

- **Core first, adapter second**: an adapter implements a core SPI (`HttpEngine`,
  `Pool`, `EventSink`) and depends on core — never the reverse, and never on another
  adapter.
- **Adapters move with the core**: core deletes a superseded API shape instead of keeping
  a compatibility overload beside it, so a compile error after a core upgrade is the
  intended migration path — adapt the call site here and rebuild. Only a **clean** build
  proves the adaptation; `Wiring` losing its previous arity is the worked example.
- **Nothing is auto-discovered**: modules are selected by config or an explicit binding.
  The benchmark CLI disables SPI discovery (`BenchApp`) because both engines sit on its
  classpath and each binds `HttpEngine` as `primary()`; an ordinary application depends on
  exactly one adapter and never meets this.
- **Engine adapters stay self-contained**: the two engines share the testkit contracts and
  the core SPI, not an internal abstraction layer. Duplication between them is deliberate
  — a shared abstraction that then needs per-engine fixes misrepresents the engines (see
  `docs/audit-structure-consistency-1.5.2.md` §6).
- The parent POM inherits its build plugins from `freeway-parent` (compiler 25, surefire,
  source, javadoc, GPG signing, Central publishing) and pins core versions through
  `<freeway.version>` in `dependencyManagement`.

## Testing

JUnit 6.1.3; tests live beside the module they cover and use the `*Test` suffix.

- **A new engine passes the testkit contracts** (`CompressionContract`,
  `ContextContract`, `RemoteRpcContract`) instead of copying another adapter's tests.
  `EngineFixture` assembles the server through `WebServerBuilder` and `Pipelines`, so a
  contract cannot depend on one engine's assembly style.
- **Tests that need a real service are gated by an environment variable**
  (`FREEWAY_TEST_KAFKA`) and must be usable both ways: skipped without it, red against a
  dead address, green against a live one.
- **A cross-process test is the only shape that can catch a bridge misconfiguration** —
  same-JVM dispatch satisfies the assertions on its own, which is why `CrossJvmEventTest`
  spawns real JVMs and `KafkaEventSinkIntegrationTest` uses two containers.
- **Reverse-check a fix**: break it, watch the test go red, restore it. A test that passes
  for the wrong reason is worse than no test.

## Regressions to Watch

- **Kafka bridge contract** — all three keys must agree across nodes:
  `freeway.kafka.topics` is the bridge topic the sink writes and the subscriber polls,
  while the *local* topic travels in the `X-Event-Topic` header;
  `freeway.kafka.allowed-event-types` must list every bridged type (including
  `java.lang.String` for string topics — an empty list accepts nothing and warns at
  startup); `freeway.kafka.client-id` must be unique per node, or suppress-own swallows
  the peer's events.
- **WebSocket sends are asynchronous**: the Jetty/Undertow sessions never block the
  calling (often receive/I/O) thread — send failures are logged rather than thrown, and
  `close()` returns immediately after starting the close handshake.
- **Engine parity is not a goal**: transport limits, dispatch, response writing, TLS/ALPN
  and 404 handling legitimately differ per engine. When touching one, run its own module's
  tests, not just the shared contracts.

## Commit Rules

- Never include `Co-Authored-By`, AI tool names, or any form of AI attribution in commit
  messages.
- Commit messages describe the change itself, never the process or tooling used.
- All commits appear under the user's name only.

## Further Reading

- Core [AGENTS.md](https://github.com/dzb/freeway/blob/main/AGENTS.md) — the repo-wide
  conventions this repository follows unchanged.
- [README.md](README.md) — which module to use, per use case.
- [docs/RELEASE.md](docs/RELEASE.md) — release and publishing steps.
- [docs/audit-structure-consistency-1.5.2.md](docs/audit-structure-consistency-1.5.2.md)
  — the current structure audit and status record.
