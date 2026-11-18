# Fleet

`freeway-cloud-fleet` contains the Fleet client, discovery adapter and registry adapter.
The wire protocol lives in `freeway-fleet-protocol`; the client depends on it. The protocol package remains `com.jujin.freeway.fleet`; the cloud adapter package is `com.jujin.freeway.cloud.fleet`.
It has no server and no in-process registry:

- `FleetModule` — binds the Fleet client, discovery/registry adapter and client-side
  load balancer;
- `FleetClient` — the wire client;
- `FleetDiscovery` / `FleetRegistry` — the `ServiceDiscovery` /
  `ServiceRegistry` adapters;
- `FleetProtocol` (in `freeway-fleet-protocol`) — routes, headers and version shared with the server.

## Assembly

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),          // runtime: health, metrics, RPC, ...
    new MemberModule(),         // register, renew, readiness, drain
    new FleetModule());         // Fleet client + client-side load balancer
```

`MemberModule` from `freeway-cloud` drives the lifecycle; the backend
never carries it. Static discovery (`StaticModule`) does not need membership;
it has no write side.

`freeway-cloud-fleet` is inert until installed: a dependency alone does not require a
Fleet process. Once `FleetModule` is placed, `freeway.fleet.nodes` must
name at least one node, or startup fails with the key in the message.

## Configuration

`FleetKeys` owns the keys shared by the client/server wire surface:

| Key | Meaning |
|---|---|
| `freeway.fleet.token` | Optional shared token sent as `X-Fleet-Token`. |
| `freeway.fleet.token-previous` | Retired token accepted by a server during rotation. |

`FleetModule.ConfigKeys` owns the client-only settings:

| Key | Meaning |
|---|---|
| `freeway.fleet.nodes` | Comma-separated Fleet addresses (`host:port` or `http(s)://host:port`). Required when the module is active. No userinfo, query, fragment or path. |
| `freeway.fleet.cache-ttl` | Discovery cache TTL; per-service overrides use the family prefix. |
| `freeway.fleet.timeout-ms` | Per-node connect/request timeout (default `3000`). |
| `freeway.fleet.fanout-min-successes` | Minimum number of nodes that must accept a write (default `1`; must not exceed the node count). |

`MemberModule.ConfigKeys` owns the endpoint identity and drain keys
(`service-id`, `service-host`, `service-scheme`, `service-port`,
`service-instance-id`, `shutdown-drain`). The default balancer's zone key is
`LoadBalancer.ConfigKeys.LOCAL_ZONE`.

An empty node list fails startup naming the key. A running Fleet that becomes
unreachable degrades discovery to the cached membership; a failed register request
fails startup loudly.

## Protocol

`FleetProtocol` is the single owner of routes, the `X-Fleet-Proto` version
and the token header. The client and the server share that class; the wire path is `/fleet`.
Writes fan out concurrently to every configured node and require
`fanout-min-successes` accepting nodes; reads union their answers and
deduplicate by instance id. A node that answers a heartbeat with 404 makes the
client report the lost lease so `MemberHook` can re-register.

## Metrics

The client records transport metrics without duplicating the core membership
counters:

```text
fleet.client.node.request.duration
fleet.client.node.errors
fleet.client.fanout.partial
```

Discovery cache counters remain core-owned:

```text
cloud.registry.cache.hits
cloud.registry.cache.refreshes
cloud.registry.cache.stale
```

Member lifecycle counters (`cloud.member.register.*`, `cloud.member.renew.*`)
remain in `freeway-cloud`'s `MemberHook`.

## Load balancing

`freeway-cloud-fleet` does not choose instances. `FleetDiscovery` returns the
candidate list; `LoadBalancerDefault` in `freeway-cloud` chooses per RPC
attempt (weighted, zone-aware). A VIP-only deployment belongs in static
discovery, not here.
