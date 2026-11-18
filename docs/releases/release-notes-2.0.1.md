# Freeway Ext 2.0.1 Release Notes (unreleased)

> Version: 2.0.1 (unreleased) | Highlight: **The extension train aligns with core 2.0.1 membership vocabulary and build versions.**

`freeway-ext` 2.0.1 is the compatibility train for Freeway core 2.0.1. The
parent version and `freeway.version` both move to `2.0.1`, so adapters build
against and publish consistently with the core release. No 2.0.1 artifact is
published yet — Maven Central's newest extension artifact is 1.5.7 — so this
page describes the train as planned, not as shipped.

## Highlights

### Fleet write adapter is named by role

`FleetRegistrar` is now `FleetRegistry`. It implements the core
`ServiceRegistry` contract and pairs with the existing `FleetDiscovery`
read-side adapter; the rename is internal and does not change Fleet's wire
protocol or configuration.

The client artifact is now `freeway-cloud-fleet`, matching
`freeway-cloud-consul`; its package is `com.jujin.freeway.cloud.fleet`.
`freeway-fleet-protocol` remains the shared wire contract and
`freeway-fleet-server` remains the deployable control plane.

Shared token keys remain in `FleetKeys`; client-only node, timeout and cache
keys live in `FleetModule.ConfigKeys`. The Fleet server's H2 dependency is now
runtime-scoped, matching its embedded default store.

### Fleet client transport and server maintenance

Fleet client writes now fan out concurrently. `freeway.fleet.fanout-min-successes`
sets the minimum accepting-node count for a write (default `1`), and a partial
fan-out is recorded rather than silently treated as a full success. The client
records only transport signals:

```text
fleet.client.node.request.duration
fleet.client.node.errors
fleet.client.fanout.partial
```

Member lifecycle counters remain in `freeway-cloud`; discovery cache counters
remain in `CachingDiscovery`.

Fleet Server is now an executable application with an embedded H2 store and
dashboard. The binary distribution contains `bin/fleet`,
`bin/fleet.bat` and `bin/fleet.ps1`; operators do not compose the
server library themselves. `demo/registry-server` remains non-published.

Fleet Server also has a `fleet-server` readiness contributor that pings its
`LeaseStore`, periodic lease cleanup, and the runtime keys:

```text
freeway.fleet.lease-ttl
freeway.fleet.eviction-interval
```

Server request, auth, protocol, eviction and latency signals are exposed under
`fleet.server.*`.

### Kubernetes discovery is a read-only adapter

`freeway-cloud-k8s` watches `discovery.k8s.io/v1` EndpointSlices and exposes
ready Pod endpoints through the core `ServiceDiscovery` contract. It does not
bind `ServiceRegistry`, install `MemberModule`, or invent a heartbeat: Kubernetes
owns service membership and readiness. In-cluster SDK configuration is
automatic; local development can point at a kubeconfig.

### Fleet and Consul follow `cloud.member`

The core now owns service membership in `MemberModule` / `MemberHook` /
`MemberReadiness` and uses:

```text
freeway.cloud.member.service-id
freeway.cloud.member.service-host
freeway.cloud.member.service-scheme
freeway.cloud.member.service-port
freeway.cloud.member.service-instance-id
freeway.cloud.member.shutdown-drain
```

Fleet and Consul continue to provide only the transport and registry/discovery
implementations. The old registration package names and member-key prefix are
not aliased; applications migrate to the core vocabulary.

`freeway.cloud.registry.type` remains the backend declaration. There is no
`local` backend type.

### Configuration aliases are inherited from core

The core 2.0.1 train accepts `f2.*` / `F2.*` configuration keys and `F2_*` /
`f2_*` environment variables as input aliases for canonical `freeway.*`. The
extension modules do not maintain a second alias vocabulary.

## Verification

```text
mvn -o clean test
  ext modules 2.0.1-SNAPSHOT — 291 tests, 0 failures, 0 errors, 7 skipped
```

The full ext reactor builds against the installed core 2.0.1-SNAPSHOT snapshot.

## Compatibility

- Core compatibility: `2.0.1-SNAPSHOT` (unreleased; the latest published core is `2.0.0`).
- Java: 25+.
- All published ext modules track `2.0.1`.
- The core member renames and key changes are not compatibility aliases; see
  the core 2.0.1 release notes for the migration table.

The complete change list is in [CHANGELOG.md](../../CHANGELOG.md).
