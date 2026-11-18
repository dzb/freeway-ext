# Fleet Server

`freeway-fleet-server` is a standalone Fleet registry application. It ships
as an executable jar and a small distribution with platform launchers, an
embedded H2 store and a read-only operations dashboard. It does not need to be
composed into an application module by the operator.

Download or unpack `fleet-2.0.1-SNAPSHOT.zip` and run:

```bash
# Linux / macOS
./bin/fleet

# Windows cmd
bin\fleet.bat

# Windows PowerShell
.\bin\fleet.ps1
```

The scripts default to `FLEET_HOST=0.0.0.0`, `FLEET_PORT=19090` and use the
embedded H2 file in the current working directory. Set `FLEET_HOST` or
`FLEET_PORT` to override them. `freeway-fleet-server` is an application
artifact, not a library for business applications; applications depend on
`freeway-cloud-fleet` (the client). `demo/registry-server` remains a
development-only demo and is not published.

The dashboard opens at:

```text
http://localhost:19090/fleet/ui
```

It lists every lease in the local node, including warming and not-ready
instances. Its admin API is protected by the same shared token as the Fleet
wire surface; set `freeway.fleet.token` before exposing the server. The
dashboard is read-only: it does not provide a second way to mutate membership.

## Surface

`FleetProtocol` defines the shared wire contract:

| Operation | Route |
|---|---|
| register | `POST /fleet/services/{serviceId}/instances` |
| renew | `POST /fleet/services/{serviceId}/instances/{instanceId}/renew` |
| unregister | `DELETE /fleet/services/{serviceId}/instances/{instanceId}` |
| discover | `GET /fleet/services/{serviceId}/instances` |
| readiness | `POST /fleet/services/{serviceId}/instances/{instanceId}/ready` |
| self-description | `GET /fleet` |
| dashboard | `GET /fleet/ui` |
| admin overview | `GET /fleet/admin/overview` |
| admin instances | `GET /fleet/admin/instances` |

The server reads `freeway.fleet.token` (writer credential) and
`freeway.fleet.token-previous` (rotation window) from `FleetKeys`, and
`freeway.fleet.db.url` / `.user` / `.password` from
`FleetServerModule.ConfigKeys`; runtime lease keys live in
`FleetServerModule.RuntimeKeys`:

| Key | Meaning |
|---|---|
| `freeway.fleet.lease-ttl` | Lease eviction horizon (default `30s`); must exceed the core member heartbeat interval. |
| `freeway.fleet.eviction-interval` | Periodic cleanup cadence (default `30s`). |

Requests may carry `X-Fleet-Proto`; a newer protocol is refused with `426`
rather than a misleading `404`. The self-description is open so a client can
learn the protocol before presenting credentials. `X-Fleet-Token` is checked
when `freeway.fleet.token` is set; `freeway.fleet.token-previous`
supports a rotation window.

## Health and metrics

`FleetServerModule` contributes a `CloudHealthContributor` named
`fleet-server`; its check pings the bound `LeaseStore`. Install `HealthModule`
(or `CloudModule`) to expose `/health/ready`. A `RuntimeHook` periodically calls
`LeaseStore.evictExpired`; the server records:

```text
fleet.server.requests
fleet.server.register.requests
fleet.server.renew.requests
fleet.server.unregister.requests
fleet.server.ready.requests
fleet.server.discover.requests
fleet.server.auth.failures
fleet.server.protocol.rejects
fleet.server.request.duration
fleet.server.eviction
fleet.server.eviction.failures
fleet.server.internal.errors
```

Install `ObserveModule` (or a replacement `Metrics` binding) to export them.
The server does not register itself into the Fleet and does not own the
application member lifecycle.

## Current maturity

The executable application is ready to run as a single-node control plane, but
it is not a production HA control plane yet:

- the embedded H2 file store persists leases for one node; a restart keeps the
  table, but there is no replication or HA coordination;
- the dashboard is read-only and protected by the shared token; there is no
  per-user RBAC or TLS built into the application itself;
- do not share one H2 file between Fleet processes; PostgreSQL is the HA path.

Those are server responsibilities and belong here, not in `freeway-cloud`.
H2 runs embedded with the process, so a single Fleet node is self-contained:
the default URL is `jdbc:h2:file:./fleet;DB_CLOSE_ON_EXIT=FALSE`.
An embedded H2 file must not be shared by two Fleet processes; for HA the
`LeaseStore` seam takes a PostgreSQL implementation. Node
discovery for the Fleet itself must be independent of the Fleet (static seed
list, an external load balancer, or the shared database) — never self-registration.

## Multi-node behavior

The client fans writes out to every configured node and unions reads. Each node
holds an independent table; a dead node is repopulated by the next heartbeat of
each instance. This is the current availability model and its limit: it is not
a consensus protocol, and a node restart can briefly hide membership until the
clients' next renewal.

## Follow-up roadmap

Post-2.0.1 control-plane work is tracked in
[`design/fleet-followups.md`](../design/fleet-followups.md): self-preservation,
status, PostgreSQL storage, TLS/authorization and protocol compatibility.
