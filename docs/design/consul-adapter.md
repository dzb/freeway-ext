# freeway-ext: Consul Service Discovery Adapter Design (Draft)

- Date: 2026-10-08
- Target: new module `freeway-cloud-consul` (`freeway-ext`)
- Purpose: provide a Consul implementation of the core's `ServiceRegistry` / `ServiceDiscovery`,
  alongside the mandatory K8s adapter, covering **non-K8s production environments** (VM / bare metal / multi-cloud)
- Basis: **source-code facts** of the core `freeway-cloud`'s existing seams (all citations below verified),
  `freeway-ext/AGENTS.md`, core `AGENTS.md` (dependency discipline, shape rules, config key rules)

## 0. Conclusion at a Glance

**K8s (mandatory) + Consul (second).** Consul was chosen for operational reality, not name recognition:

1. **No external storage** — the Consul server ships with Raft + local BoltDB, so production needs no database; this is the most substantive difference from Nacos (which requires MySQL in production), removing one operational layer.
2. **Isomorphic model** — the four methods map almost 1:1 to Consul's registration/health/deregistration/query.
3. **Zero new dependencies for the adapter** — plain JDK `HttpClient` is all it takes, consistent with the core's dependency discipline; a single local process suffices for end-to-end testing.
4. **Covers residual scenarios that K8s cannot** — VM / bare metal / multi-cloud.

This document describes only the minimum viable design for this version and explicitly lists the parts that are **not done**.

## 1. Goals / Non-Goals

**Goals**

- Provide a Consul implementation of `ServiceRegistry` + `ServiceDiscovery` so that `freeway.cloud.registry.type=consul` / `freeway.cloud.discovery.type=consul` take effect.
- Reuse the core's existing seams: add no interfaces and change no core code.

**Non-Goals (explicitly not done, see §7)**

- Consul KV / Config (freeway has its own config cascade; no overlapping work)
- Consul Connect / mTLS
- Multi-datacenter federation
- Retrieving tokens through `SecretStore` (Vault)

## 2. Existing Seams (core, verified)

| seam | Location | Description |
|---|---|---|
| `ServiceRegistry` | `cloud.discovery` | `register(ServiceInstance)` / `boolean renew(serviceId, instanceId)` / `unregister(ServiceInstance)` / `default Duration drainWindow()` |
| `ServiceDiscovery` | `cloud.discovery` | `List<ServiceInstance> instances(serviceId)` |
| `ServiceInstance` | `cloud.discovery` | `record(serviceId, instanceId, Endpoint endpoint, Map<String,String> metadata)` |
| `Endpoint` | `cloud.discovery` | `record(scheme, host, port, basePath)`; `uri()` renders `scheme://host:port+basePath` |
| Backend selection key | `CloudModule.ConfigKeys` | `freeway.cloud.registry.type` / `freeway.cloud.discovery.type`; `local` = built-in |
| Replacement method | `@Local` + `.primary()` | The built-in default carries `.marker(Local.class)`; an adapter overrides it with `.primary()` |
| Ineffective-setting warning | `BackendTypeGuard.warnIfExternal` | An external type is configured but the local one is still active → startup WARN; silent automatically once the adapter is primary |
| Lifecycle | `RegistryLifecycleHook` | Registers `ServiceDeclaration` after startup; `renew` every 10s; `unregister` on stop; **re-registers** when `renew==false` |
| Health | `RegistryRenewal` | `renew` fails 3 times in a row → `/health/ready` turns unhealthy |
| Drain | `drainWindow()` | With `freeway.cloud.registry.shutdown-drain=auto`, asks the adapter for the propagation window |

**Key inference**: the adapter only needs to implement two interfaces + one module + one `Wiring`.
**Lifecycle / health / re-registration / drain are all driven by the core's existing code; the adapter does not reimplement them.**

## 3. Interface → Consul HTTP API Mapping

| freeway | Consul HTTP API |
|---|---|
| `register(instance)` | `PUT /v1/agent/service/register`, body below |
| `renew(sid, iid)` | `PUT /v1/agent/check/pass/{sid}:{iid}` → 2xx=true; 404/other=false |
| `unregister(instance)` | `PUT /v1/agent/service/deregister/{sid}:{iid}` |
| `instances(sid)` | `GET /v1/health/service/{sid}?passing=true` |
| `drainWindow()` | Returns `freeway.cloud.consul.drain-window` (default `PT5S`) |

The body shape of `register`:

```json
{
  "ID":   "{serviceId}:{instanceId}",
  "Name": "{serviceId}",
  "Address": "{endpoint.host}",
  "Port":    "{endpoint.port}",
  "Meta":  { "freeway-scheme": "...", "freeway-base-path": "...", ...instance.metadata },
  "Check": { "CheckID": "{serviceId}:{instanceId}", "TTL": "15s",
             "Status": "passing", "DeregisterCriticalServiceAfter": "1m" }
}
```

Mapping notes:

- **instanceId ↔ Consul `Service.ID`**: use `{serviceId}:{instanceId}` (Consul requires the ID to be unique within the agent). `Name` = `serviceId`.
- **`renew` semantics aligned**: the core requires "return false when the registry no longer recognizes the instance". Once the TTL check is cleared / the instance is deregistered, `check/pass` returns 404 → false, which is exactly what triggers the core's **re-registration** logic.
- **`instances` returns only healthy instances**: `?passing=true` matches the "live and ready" contract of `ServiceDiscovery.instances`.
- **`metadata`**: `ServiceInstance.metadata` ↔ Consul `Service.Meta` (a string map, naturally aligned).
- **`Endpoint.scheme` / `basePath` land in `Meta`** (see §4; this is a fidelity requirement, not optional).

### 3.1 Live-Agent Verification Corrections (added after running against a live Consul agent)

The following four points surface only against a real agent; a stub cannot detect them:

1. **`Meta` keys cannot contain `.`** — Consul returns 400 outright: `Key contains invalid characters`. Reserved keys therefore use hyphens: `freeway-instance-id` / `freeway-scheme` / `freeway-base-path`.
2. **TTL checks start as `critical`** — without setting `"Status": "passing"`, the instance cannot be found by `?passing=true` between registration and the first heartbeat, leaving a discovery blind spot for a whole renew cycle on every (re)start.
3. **The check id must be explicit** — the id Consul derives for a service's inline check is `service:{serviceID}`, while renew passes the service id; without `"CheckID"`, `check/pass` always returns 404. The adapter writes an explicit `CheckID`.
4. **Errors must carry the response body** — Consul writes the rejection reason in the body (as in the Meta error above); throwing only a status code sends people to the documentation even though the agent has already explained it.

## 4. Why `scheme` / `basePath` Must Be Preserved (code facts)

```java
// CloudHttpClientDefault:425 — the real outbound URL is assembled here
String url = instance.endpoint().uri() + request.path();

// Endpoint:71
URI.create(scheme + "://" + host + ":" + port + basePath)
```

- `scheme` also determines the mesh's ws/wss (`CloudEventLifecycleHook:102`)
- `basePath` is the prefix when the service is mounted under a path prefix

Consul natively has only `Address` + `Port`. **Dropping `scheme`/`basePath` is not a simplification but a silent failure**: an https service would be called over http, and a path-prefixed service would return 404.

**Approach (keep it simple)**: write the two keys `freeway-scheme` / `freeway-base-path` into Consul `Meta`; **omit them entirely when `http` and basePath is empty**; on read, missing values default to `http` / `""`. A normal service's payload is therefore exactly as clean as with the "drop it" approach, while the few services that need it are not broken.

## 5. Component Design

```
freeway-cloud-consul/
├─ ConsulModule            ModuleEx: bind(ServiceRegistry/Discovery).primary()
│                                    + contributes KnownKeys.of(ConfigKeys.class, PREFIX)
│                                    + fail-loud at startup on invalid config
├─ ConsulWiring            record: agentHost/agentPort/scheme/token/ttl/drainWindow/timeout
│                                    defaults() + per-field withX()
├─ ConsulClient            thin HTTP client: JDK HttpClient only
│                                    register / renew / unregister / instances
├─ ConsulServiceRegistry   implements ServiceRegistry
├─ ConsulServiceDiscovery  implements ServiceDiscovery
└─ ConfigKeys              nested class, all-literal keys
```

- **`ConsulWiring`**: 3+ optional inputs → a parameter record + `defaults()` + per-field withers (matching the core's shape rules), with no overload ladder.
- **`ConsulClient`**: plain `java.net.http.HttpClient`, zero third-party dependencies; integration tests can use a local consul or a JDK `HttpServer` stub.
- **`ConsulModule`**: does exactly three things — `.primary()` binding, vocabulary contribution, and startup fail-loud.
- **No duplication** of the core's lifecycle/health/drain — they read the interfaces and take effect automatically once the adapter is primary.

## 6. Config Keys (following core rules)

`ConfigKeys` is nested in `ConsulModule`, uses full literals, sets `PREFIX = "freeway.cloud.consul"`, and calls `contribute(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX))`:

| Key | Default | Description |
|---|---|---|
| `freeway.cloud.consul.agent-host` | `127.0.0.1` | Consul agent address |
| `freeway.cloud.consul.agent-port` | `8500` | HTTP port |
| `freeway.cloud.consul.scheme` | `http` | `http` / `https` |
| `freeway.cloud.consul.token` | *(empty)* | ACL token; when empty it is not sent (see §7) |
| `freeway.cloud.consul.ttl` | `15s` | Health TTL; should be > the renew interval (10s) |
| `freeway.cloud.consul.drain-window` | `PT5S` | Deregistration propagation window |

The enablement switch follows the core's established `freeway.cloud.registry.type=consul` / `freeway.cloud.discovery.type=consul`.

## 7. Explicitly Not Done (with reasons)

| Not Done | Reason |
|---|---|
| Retrieving tokens with `SecretStore` | It would make the **discovery plane depend on the secret plane** (startup-order coupling, and `SecretStore`'s own backend is still missing). The token goes through the `token` key + env injection, never a plaintext file, matching deployment convention. Left as a future option |
| mTLS / Connect client certificates | That is the service mesh's responsibility, not the discovery adapter's. `https` + an optional token suffices |
| Custom TLS config keys | A private CA uses the JVM default truststore (`-Djavax.net.ssl.trustStore=...`), adding no new concept |
| Configurable datacenter | A rare scenario that only matters under multi-DC federation; it belongs to Consul's own federation/DNS |
| Configurable `passing` | `passing=true` is the **contract** of `ServiceDiscovery.instances` ("live and ready"), not a knob; returning unhealthy instances violates the contract |

## 8. Test Strategy

| Layer | Approach |
|---|---|
| Unit | JDK `com.sun.net.httpserver.HttpServer` as a **Consul agent stub**, asserting request paths/bodies (including `CheckID`/`Status`), `renew` 404→false and non-2xx throwing, `instances` parsing (including the `Meta` fallback to `http`/`""`) and skipping of malformed entries |
| Contract | The "register → discover → renew → deregister → discovery empty" loop; a real consul binary started locally (skipped via `assumeTrue` when absent, matching the gating of the ext's existing Kafka tests) |
| Integration | `FreewayApp` + `ConsulModule.primary()`, asserting `isActiveBinding(ServiceRegistry, Local.class)==false`, that `BackendTypeGuard` no longer warns, and that `/health/ready` reflects the renew result |

## 9. Implementation Phases

1. `ConsulWiring` + `ConsulClient` (thin HTTP wrapper) + unit tests (stub)
2. `ConsulServiceRegistry` / `ConsulServiceDiscovery` + mapping unit tests
3. `ConsulModule` (primary binding + key table + fail-loud)
4. End-to-end (gated on a real consul) + behavioral tests for `/health/ready` and `drainWindow`

## 10. Open Questions

(None after convergence. If implementation surfaces details such as the TTL lower bound or the `DeregisterCriticalServiceAfter` value, settle them during implementation per the Consul documentation without changing this design's external shape.)
