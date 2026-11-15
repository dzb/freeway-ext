# Fleet Server

`freeway-fleet-server` is a standalone registry server module. Compose it with
`freeway-boot` and `freeway-http` to run a Fleet process:

```java
FreewayApp.run(new HttpModule(), new FleetServerModule());
```

It is a control plane: it owns the registry surface and a `LeaseStore`
(default `H2LeaseStore`, embedded H2; `MemLeaseStore` for tests), and it does
not register itself into the Fleet it serves. Applications depend on
`freeway-fleet` (client), never on this module.

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

The server reads `freeway.fleet.token` (writer credential) and
`freeway.fleet.token-previous` (rotation window) from `FleetKeys`, and
`freeway.fleet.db.url` / `.user` / `.password` from
`FleetServerModule.ConfigKeys`.

Requests may carry `X-Fleet-Proto`; a newer protocol is refused with `426`
rather than a misleading `404`. The self-description is open so a client can
learn the protocol before presenting credentials. `X-Fleet-Token` is checked
when `freeway.fleet.token` is set; `freeway.fleet.token-previous`
supports a rotation window.

## Current maturity

The module is a deployable starting point, not a production control plane yet:

- the store is in-memory and at-most-once; a restart loses membership until
  clients re-register;
- no TLS and no admin UI/RBAC;
- no durable leases or HA coordination.

Those are server responsibilities and belong here, not in `freeway-cloud`.
H2 runs embedded with the process, so a single Fleet node is self-contained:
the default URL is `jdbc:h2:file:./fleet;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE`.
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
