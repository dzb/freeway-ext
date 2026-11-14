# freeway-ext documentation

Reference material for Freeway's third-party integrations. Framework-wide
documentation lives in the core repository: [`freeway/docs`](https://github.com/dzb/freeway/tree/main/docs).

## Map

| Area | Document |
|---|---|
| Repository conventions | [`AGENTS.md`](../AGENTS.md) |
| Structural consistency audit (1.5.2) | [`audits/structure-consistency-1.5.2.md`](audits/structure-consistency-1.5.2.md) |
| Adapter contract audit (1.5.7) | [`audits/adapter-contracts-1.5.7.md`](audits/adapter-contracts-1.5.7.md) |
| Consul adapter design | [`design/consul-adapter.md`](design/consul-adapter.md) |
| Benchmark protocol reports | [`benchmarks/`](benchmarks/) |
| Release and publishing steps | [`release.md`](release.md) |
| Release notes | [`releases/`](releases/) |

## Conventions

- Documentation directories use plural category names (`audits/`, `benchmarks/`,
  `design/`, `releases/`) and lower-case kebab-case file names.
- A document lives in the most specific directory that describes its role; audit
  and benchmark reports are historical records and are not normative contracts.
- Documentation is written in English from Freeway 2.0 on.
