# Registry Server Demo

A single freeway process acting as a **Fleet registry server** — the
`LeaseStore` it owns is exposed over HTTP so that **other** freeway
instances (running in the same or other JVMs) can register their own
instances and discover each other. It is a development helper: best-effort, no
auth, no TLS.

## What it demonstrates

| Capability | Where it shows |
|---|---|
| One `LeaseStore` behind both protocols | POST `/fleet/.../instances` then GET that same name lists it |
| Dev registry usable from any freeway app | any instance can POST its `ServiceInstance` and get discovered |
| Same sync semantics as the server | renew POST keeps `alive` true, DELETE removes, renew of a ghost is 404 |
| The process itself is also a registry client | on start it registers `serviceId=freeway-app` into the same store |

## Endpoints

```text
POST   /fleet/services/{serviceId}/instances              register a ServiceInstance (JSON body)
GET    /fleet/services/{serviceId}/instances               discover live instances
POST   /fleet/services/{serviceId}/instances/{instanceId}/renew   renew (returns {"alive":true})
DELETE /fleet/services/{serviceId}/instances/{instanceId}  unregister
```

## Prerequisites

- JDK 25
- matching freeway artifacts in the local Maven repo:
  ```bash
  cd freeway && mvn install -DskipTests
  cd ../freeway-ext
  ```

## Run

```bash
cd demo/fleet-server
./run.sh            # builds the shaded jar and starts the server
```

Expected startup:

```
== registry-server starting on http://127.0.0.1:19090 ==
...
[registry-server] ready — listening until killed
```

Then, from another terminal:

```bash
curl -s -X POST localhost:19090/fleet/services/order/instances \
  -H 'Content-Type: application/json' \
  -d '{"serviceId":"order","instanceId":"i7","endpoint":{"scheme":"http","host":"127.0.0.1","port":18090,"basePath":""},"metadata":{}}'
curl -s localhost:19090/fleet/services/order/instances
curl -s -X POST localhost:19090/fleet/services/order/instances/i7/renew
curl -s -X DELETE localhost:19090/fleet/services/order/instances/i7
```

## Note on the split with production

The demo runs `FleetServerModule`; its embedded H2 `H2LeaseStore` keeps it
self-contained while persisting leases in `./fleet.mv.db`. Production
deployments add auth/TLS/HA at the server layer, and move to a shared
PostgreSQL store when more than one Fleet node must see the same leases.
