# Freeway Ext 2.0.0 Release Notes

> Version: 2.0.0 | Highlight: **Adapters compose the 2.0 HTTP/WebSocket SPIs, readiness is an explicit registry capability, and every module tracks core 2.0.0.**

`freeway-ext` 2.0.0 ships on the core 2.0.0 train. The parent version and
`freeway.version` both move to `2.0.0`, so this repository builds against, and
is versioned with, the core it extends. These are contract migrations, not a
new adapter feature set.

## Highlights

### HTTP adapters compose the 2.0 seam

Core 2.0 deleted `AbstractHttpContext` / `AbstractWebSocketSession`. The
Undertow and Jetty adapters now compose the two core SPIs and final policy
objects:

- `UndertowHttpTransport` / `JettyHttpTransport` implement `HttpTransport`;
- `UndertowWebSocketTransport` / `JettyWebSocketTransport` compose
  `WebSocketTransport` and `WebSocketSessionSupport`;
- status/header validation, `Date`, `Content-Length`, gzip, `Vary`, close-reason
  truncation and the body limit have one implementation in core;
- per-request transport/support objects remove the old `ThreadLocal` pools and
  their reset obligations.

### Readiness is a capability, not a flag

`FleetRegistrar` and `ConsulServiceRegistry` implement `ReadinessAwareRegistry`.
They no longer claim `supportsReadiness()` or inherit inert readiness defaults
from `ServiceRegistry`; the core registration hook receives a usable capability
through `ServiceRegistry.readinessCapability()` and otherwise uses the base
one-write registration path.

### Fleet and Kafka keep their explicit planes

Fleet remains the first-party client/server registry pair in this repository.
Kafka remains a separate durable plane: the wire topic is the routing topic,
subscriptions declare the only decodable payload types, and client-owned commit
keys cannot be overridden through properties.

## Breaking changes

- Ext modules compile against core `2.0.0`; core renames and removed SPIs are
  not aliased.
- Consul/Fleet registration implementations follow
  `ReadinessAwareRegistry`.
- HTTP/WebSocket adapter subclasses of deleted core base classes are replaced
  by transport/support composition.
- The benchmark CLI follows `publishEvent` / `EventSubscriber.ofEvent`.
- The Consul input bundle is `ConsulOptions`; it contains caller-selectable
  values, not collaborator wiring.
- The built-in engine is assembled as `FreewayHttpEngine.Deps` +
  `FreewayHttpEngine.Options`; the benchmark harness follows that split.

## Verification

```text
mvn clean test
  ext modules 2.0.0 — 188 tests, 0 failures, 0 errors, 0 skipped
```

The full ext reactor builds against installed core 2.0.0 artifacts.

## Compatibility

- Core compatibility: `2.0.0`.
- Java: 25+.
- All published ext modules track `2.0.0`.
- The complete change list is in [CHANGELOG.md](../../CHANGELOG.md).
