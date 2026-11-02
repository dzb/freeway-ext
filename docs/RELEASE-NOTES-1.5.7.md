# Freeway Ext 1.5.7 Release Notes

> Version: 1.5.7 | Highlight: **Adapter header budgets and Consul readiness match the core contracts; Kafka refuses client-owned overrides.**

1.5.7 ships on the core 1.5.7 train: the parent version and `freeway.version`
both move to `1.5.7`, so this repo builds against, and is versioned with, the
core it extends. The changes are contract fixes in the adapters rather than new
surface.

## Highlights

### One header budget in both engines (freeway-http-jetty / freeway-http-undertow)

`freeway.http.server.max-header-bytes` (default 16 KiB) is now honored by both
adapters. Undertow maps it to `MAX_HEADER_SIZE` and the HTTP/2
`HTTP2_SETTINGS_MAX_HEADER_LIST_SIZE`; Jetty uses one `HttpConfiguration` for
all branches, including plaintext (the bare `ServerConnector` that silently kept
Jetty's default is gone). The shared `HeaderBudgetContract` in the adapter
testkit pins the budget for both engines.

### Consul readiness follows the core seam (freeway-cloud-consul)

`register(instance, Health)` writes the TTL check as `passing`/`critical`
according to the reported health, and `markReady` calls the agent's
`/v1/agent/check/pass|fail` endpoints. A 404 from `markReady` returns `false`
so the core heartbeat can re-register; other non-2xx responses throw, making
the node unhealthy instead of looping a registration that will fail the same
way. The starting → passing path is covered by tests.

### Kafka refuses overrides of client-owned keys (freeway-mq-kafka)

`freeway.kafka.properties` can no longer set `enable.auto.commit`, group,
cluster or (de)serializer keys. `enable.auto.commit=true` would advance offsets
outside the subscriber's explicit commit and silently turn at-least-once into
at-most-once; startup now rejects it and names the key.

### Consul module assembly is stated

`ConsulModule` binds `ServiceRegistry`/`ServiceDiscovery` primary and must be
placed with `CloudModule` (or at least `CloudDiscoveryModule`); the module's
documentation now says so. The container declares one module class once, so it
cannot be a `@SubModule` of the cloud bundle.

## Build

The release uses `central-publishing-maven-plugin` 0.12.0, matching core. 0.11
put Maven's local metadata files into the bundle and Central rejected the
bundle; 0.12 removes them before upload.

## Compatibility

- All ext modules track core `1.5.7`; `freeway.version` and the parent version
  are `1.5.7`.
- No public adapter API was added in this release; the changes are behavior and
  contract tests. The full list is in [CHANGELOG.md](../CHANGELOG.md).

## Numbers

- 8 modules, **212 tests (7 environment-gated skips)**; `spotless:check` passes.
- Core compatibility: built and tested against core `1.5.7`.
