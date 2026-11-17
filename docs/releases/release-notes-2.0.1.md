# Freeway Ext 2.0.1 Release Notes (unreleased)

> Version: 2.0.1 (unreleased) | Highlight: **The extension train aligns with core 2.0.1 membership vocabulary and build versions.**

`freeway-ext` 2.0.1 is the compatibility train for Freeway core 2.0.1. The
parent version and `freeway.version` both move to `2.0.1`, so adapters build
against and publish consistently with the core release. No 2.0.1 artifact is
published yet — Maven Central's newest extension artifact is 1.5.7 — so this
page describes the train as planned, not as shipped.

## Highlights

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
  ext modules 2.0.1-SNAPSHOT — 255 tests, 0 failures, 0 errors, 7 skipped
```

The full ext reactor builds against the installed core 2.0.1-SNAPSHOT snapshot.

## Compatibility

- Core compatibility: `2.0.1-SNAPSHOT` (unreleased; the latest published core is `2.0.0`).
- Java: 25+.
- All published ext modules track `2.0.1`.
- The core member renames and key changes are not compatibility aliases; see
  the core 2.0.1 release notes for the migration table.

The complete change list is in [CHANGELOG.md](../../CHANGELOG.md).
