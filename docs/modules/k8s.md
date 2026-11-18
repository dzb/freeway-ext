# Kubernetes Discovery

`freeway-cloud-k8s` is the read-only Kubernetes discovery adapter for
`freeway-cloud`. It exposes ready Pod endpoints as `ServiceInstance` values and
does not participate in application-side registration.

## Model

```text
Kubernetes Service / EndpointSlice controller
        owns membership, readiness, eviction and replacement

freeway-cloud-k8s
        implements ServiceDiscovery only
        never implements ServiceRegistry
```

The module deliberately does not bind `ServiceRegistry`, install
`MemberModule`, or create a no-op registration path. Kubernetes owns those
facts; a no-op registry would hide that ownership and make the member lifecycle
claim a registration that never happened.

Applications still expose `/health/live` and `/health/ready` for their own
process and dependency state. Kubernetes probes consume those endpoints; the
Kubernetes discovery adapter does not turn them into a registry heartbeat.

## Assembly

```java
FreewayApp.run(
    new HttpModule(),
    new CloudModule(),
    new K8sModule());
```

`MemberModule` is not part of this composition. Place it only when a real
`ServiceRegistry` backend is also installed.

## Discovery source

The adapter uses the Fabric8 Kubernetes client and watches
`discovery.k8s.io/v1` `EndpointSlice` objects in one namespace. SDK types stay
package-private. For `instances(serviceId)` it selects slices with:

```text
kubernetes.io/service-name = serviceId
```

Only endpoints whose `conditions.ready` is `true` and whose `terminating` flag
is not `true` are returned. UDP ports, unready endpoints and terminating
endpoints are ignored. The informer cache is the local snapshot; no additional
`CachingDiscovery` wrapper is needed.

`ServiceInstance` mapping:

| Field | Source |
|---|---|
| `serviceId` | requested Kubernetes Service name |
| `instanceId` | target Pod UID, then namespace/name, then address:port |
| `endpoint.host` | ready endpoint address |
| `endpoint.port` | selected EndpointSlice port |
| `endpoint.scheme` | `freeway.cloud.k8s.scheme` |
| `metadata.zone` | EndpointSlice zone, then topology zone |
| `metadata.node` | endpoint node name |
| `metadata.hostname` | endpoint hostname |

The adapter does not invent weight, version or canary metadata. The zone is
mapped only when Kubernetes actually supplies one; values Kubernetes does not
provide remain absent.

## Configuration

| Key | Meaning |
|---|---|
| `freeway.cloud.k8s.namespace` | Namespace to watch; blank uses the client's in-cluster/kubeconfig namespace. |
| `freeway.cloud.k8s.port-name` | EndpointSlice port name; required when a Service exposes multiple TCP ports. |
| `freeway.cloud.k8s.scheme` | Published endpoint scheme, `http` or `https` (default `http`). |
| `freeway.cloud.k8s.base-path` | Optional base path included in every endpoint. |
| `freeway.cloud.k8s.kubeconfig` | Optional kubeconfig path for local development; blank uses SDK auto-configuration. |
| `freeway.cloud.k8s.resync-period` | Informer resync period, default `30s`; zero disables resync. |

In-cluster configuration uses the Pod service account. The adapter never reads
Kubernetes credentials through Freeway configuration keys.

## Required RBAC

For the normal namespace-scoped deployment:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: freeway-k8s-discovery
  namespace: default
rules:
  - apiGroups: ["discovery.k8s.io"]
    resources: ["endpointslices"]
    verbs: ["get", "list", "watch"]
```

The module does not require Service, Secret, Pod or Node read access for the
basic EndpointSlice path.

## Boundaries

Not implemented here:

- application-side registration or heartbeat;
- Endpoints (the deprecated API);
- Ingress, Gateway API, port-forward or Operator behaviour;
- ServiceRegistry or drain handling;
- custom load balancing. `LoadBalancerDefault` in `freeway-cloud` chooses among
  the returned candidates.
