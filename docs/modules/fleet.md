# Fleet

`freeway-fleet` contains the Fleet client, discovery adapter and registrar.
The wire protocol lives in `freeway-fleet-protocol`; the client depends on it.
It has no server and no in-process registry:

- `FleetModule` — binds the Fleet client, discovery/registrar and client-side
  load balancer;
- `FleetClient` — the wire client;
- `FleetDiscovery` / `FleetRegistrar` — the `ServiceDiscovery` /
  `ServiceRegistry` adapters;
- `FleetProtocol` (in `freeway-fleet-protocol`) — routes, headers and version shared with the server.

## Assembly

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),          // runtime: health, metrics, RPC, ...
    new RegistrationModule(),   // register, renew, readiness, drain
    new FleetModule());         // Fleet client + client-side load balancer
```

`RegistrationModule` from `freeway-cloud` drives the lifecycle; the backend
never carries it. Static discovery (`StaticModule`) does not need registration;
it has no write side.

`freeway-fleet` is inert until installed: a dependency alone does not require a
Fleet process. Once `FleetModule` is placed, `freeway.fleet.nodes` must
name at least one node, or startup fails with the key in the message.

## Configuration

`FleetKeys` owns the shared client/server keys:

| Key | Meaning |
|---|---|
| `freeway.fleet.nodes` | Comma-separated Fleet addresses (`host:port` or `http(s)://...`). Required when the module is active. |
| `freeway.fleet.token` | Optional shared token sent as `X-Fleet-Token`. |
| `freeway.fleet.cache-ttl` | Discovery cache TTL; per-service overrides use the family prefix. |
| `freeway.fleet.timeout-ms` | Per-node connect/request timeout (default `3000`). |
| `freeway.fleet.token-previous` | Retired token accepted by a server during rotation. |

`RegistrationModule.ConfigKeys` owns the endpoint identity and drain keys
(`service-id`, `service-host`, `service-scheme`, `service-port`,
`service-instance-id`, `shutdown-drain`). The default balancer's zone key is
`LoadBalancer.ConfigKeys.LOCAL_ZONE`.

An empty node list fails startup naming the key. A running Fleet that becomes
unreachable degrades discovery to the cached membership; a failed registration
fails startup loudly.

## Protocol

`FleetProtocol` is the single owner of routes, the `X-Fleet-Proto` version
and the token header. The client and the server share that class; the wire path is `/fleet`.
Writes fan out to every configured node; reads union their answers and
deduplicate by instance id. A node that answers a heartbeat with 404 makes the
client report the lost lease so `RegistrationHook` can re-register.

## Load balancing

`freeway-fleet` does not choose instances. `FleetDiscovery` returns the
candidate list; `LoadBalancerDefault` in `freeway-cloud` chooses per RPC
attempt (weighted, zone-aware). A VIP-only deployment belongs in static
discovery, not here.
