# Fleet Follow-up Roadmap

This document records work intentionally left outside the Fleet 2.0.1 release.
It is a backlog, not a shipped contract. Each item names the owning module, the
reason it exists, and the acceptance condition that closes it.

## Shipped in 2.0.1

Do not reopen these ownership questions as part of follow-up work:

- `freeway-cloud` owns membership lifecycle, heartbeat scheduling, readiness
  gating, drain and `cloud.member.*` metrics.
- `freeway-cloud-fleet` owns the Fleet wire transport, concurrent fan-out,
  per-node results, `freeway.fleet.fanout-min-successes` and
  `fleet.client.*` transport metrics.
- `freeway-fleet-server` owns the control plane, `LeaseStore`, lease TTL,
  periodic eviction, `fleet-server` health and `fleet.server.*` metrics.
- `freeway-fleet-protocol` owns the wire routes, headers and protocol version.
- `freeway-cloud-k8s` is discovery-only; Kubernetes owns membership.

## P1 -- next production hardening

### 1. Fleet client node backoff and ejection

**Owner:** `freeway-cloud-fleet`

Today the client sends to every configured node on every operation. A node
that fails repeatedly still receives traffic. Add client-local failure memory:

- consecutive failures per node;
- bounded backoff after repeated failures;
- half-open probe after the backoff expires;
- aggregate `fleet.client.node.errors` continues to expose the cause.

Do not add a second service-health registry in Fleet. This is transport-local
failure memory only.

**Acceptance:** a dead node is skipped after the configured threshold, a
recovered node rejoins without restarting the application, and membership
lifecycle remains in `freeway-cloud`.

### 2. Bounded fan-out parallelism

**Owner:** `freeway-cloud-fleet`

The 2.0.1 client uses one virtual thread per node. Add a bounded fan-out
configuration for deployments with a large node list:

- `freeway.fleet.fanout-parallelism`;
- default `min(nodes, 16)`;
- preserve per-node timeouts and interruptability.

**Acceptance:** a large fake fleet never runs more than the configured number
of concurrent requests, and `fanout-min-successes` semantics are unchanged.

### 3. Cache invalidation after writes

**Owner:** `freeway-cloud-fleet` (cache policy remains in `freeway-cloud`)

`CachingDiscovery` currently hides a write for up to the configured cache TTL.
After a successful `register`, `markReady` or `unregister`, invalidate only the
affected service id.

Do not add a watch API to the base `ServiceDiscovery` contract for this. First
add a small invalidation capability to the cache wrapper or a Fleet-local
callback.

**Acceptance:** a registration or unregister is visible to the next discovery
call for that service, while unrelated service caches are untouched.

### 4. Fleet Server eviction self-preservation

**Owner:** `freeway-fleet-server`

A large burst of expired leases can be a Fleet server/network incident rather
than a fleet-wide outage. Add a conservative grace policy:

- measure evictions per cleanup pass;
- suppress a cleanup pass when the expired ratio crosses a configured threshold;
- emit `fleet.server.eviction.suppressed`;
- keep the existing TTL as the correctness backstop.

**Acceptance:** a mass-expiry test does not empty the store in one pass,
while a normal single-node expiry still evicts as before.

## P2 -- operate and scale

### 5. Protected Fleet Server status surface

**Owner:** `freeway-fleet-server`

Add a token-protected operations endpoint, for example:

```text
GET /fleet/status
```

It should report protocol version, store status, active lease count, lease TTL
and eviction interval. Keep the existing `/fleet` self-description open; do
not expose the status surface without auth.

**Acceptance:** status is unavailable without the configured token, reports a
failed store, and does not expose instance payloads.

### 6. Watch / long-poll discovery

**Owner:** `freeway-cloud-fleet` + `freeway-fleet-server`

This is deferred until the core discovery contract is deliberately extended.
The current `ServiceDiscovery.instances(serviceId)` has no subscription model;
Fleet should not force one into the base seam.

Evaluate in this order:

1. Fleet-local cache invalidation (P1);
2. optional long-poll endpoint;
3. an optional watch capability, not a breaking change to `ServiceDiscovery`.

**Acceptance:** a changed membership set reaches a subscriber without waiting
for the cache TTL, and a backend that never watches remains source-compatible.

### 7. PostgreSQL LeaseStore

**Owner:** `freeway-fleet-server` and a separate store artifact

H2 is the embedded single-node default. The HA path is a shared durable store,
not replication inside Fleet.

Deliver:

- a PostgreSQL `LeaseStore` implementation;
- schema creation/versioning and migration;
- connection-pool integration through the existing DB module;
- health and eviction tests against PostgreSQL.

**Acceptance:** two Fleet Server processes use one shared lease table without
sharing an H2 file, and a restart does not require a full client re-register
cycle.

### 8. TLS, mTLS and authorization

**Owner:** `freeway-fleet-server` deployment, with `freeway-http` TLS

Fleet Server 2.0.1 has a shared token and token rotation but no Fleet-specific
TLS or per-service authorization. Use the HTTP module's TLS support or a TLS
terminating proxy, and add:

- TLS/mTLS deployment guidance and a tested example;
- per-service write authorization if multi-tenant use appears;
- explicit request/body/instance-count limits.

**Acceptance:** token and body are never sent over plain HTTP in the
documented production shape, and a client authorized for one service cannot
write another when authorization is enabled.

### 9. Protocol compatibility matrix

**Owner:** `freeway-fleet-protocol` with both client and server tests

Add fixtures and executable compatibility tests for:

- current client to current server;
- older client to current server;
- current client to older server;
- malformed protocol and readiness inputs;
- route/header/body fixtures.

**Acceptance:** a protocol change cannot merge without an explicit compatibility
decision and a test covering the rejected combination.

## Explicitly not planned

- Raft, gossip or a second consensus protocol inside Fleet.
- Fleet nodes discovering themselves through Fleet.
- Turning Fleet into a configuration center, gateway or full admin UI.
- Duplicating member heartbeat/readiness state in `freeway-cloud-fleet`.
- A breaking watch/subscription method on the base `ServiceDiscovery` seam.

## Related documents

- [Fleet client/server](modules/fleet.md)
- [Fleet Server](modules/fleet-server.md)
- [Kubernetes discovery](modules/k8s.md)
