# Freeway Ext HTTP Engine Benchmark — 1.3.6-SNAPSHOT

**Date**: 2026-08-04
**Scenario**: `ping` (GET `/ping` → 200 `pong`), keep-alive mode
**Load**: 3,000 requests per iteration, 300 warmup requests, 3 iterations per combination
**Reported value**: median of the 3 iterations

## Environment

| Item | Value |
| --- | --- |
| JDK | OpenJDK 64-Bit Server VM 25.0.4+7 (Red Hat, Inc.) |
| OS | Linux 7.1.5-201.fc44.x86_64 (amd64) |
| CPU | amd64, 16 threads |
| Freeway Ext | 1.3.6-SNAPSHOT (commit `441d805`) |

## Results

### Throughput (RPS)

| Engine | Concurrency 8 | Concurrency 16 | Concurrency 32 |
| --- | ---: | ---: | ---: |
| freeway | 17.2k | 38.4k | **91.6k** |
| jdk-native | 15.8k | 50.2k | 76.0k |
| undertow-native | **20.3k** | 38.4k | 85.7k |
| undertow-adapter | 17.3k | **57.8k** | 89.0k |
| jetty-native | 14.3k | 28.1k | 46.5k |
| jetty-adapter | 25.1k | 49.1k | 64.2k |

### Latency (p50 / p95 / p99, μs)

| Engine | Concurrency 8 | Concurrency 16 | Concurrency 32 |
| --- | --- | --- | --- |
| freeway | 385 / 889 / 1418 | 270 / 1018 / 2187 | 249 / 598 / 1081 |
| jdk-native | 459 / 875 / 1160 | 264 / 586 / 929 | 333 / 819 / 1571 |
| undertow-native | 324 / 753 / 1201 | 354 / 791 / 1231 | 318 / 699 / 1136 |
| undertow-adapter | 374 / 849 / 1838 | **228 / 448 / 676** | 300 / 704 / 1297 |
| jetty-native | 479 / 1057 / 1414 | 479 / 1088 / 1539 | 552 / 1523 / 2631 |
| jetty-adapter | 267 / 608 / 1235 | 290 / 546 / 760 | 406 / 953 / 2753 |

## Observations

- **Peak throughput (concurrency 32)**: freeway (91.6k) edges out
  undertow-adapter (89.0k) and undertow-native (85.7k); jdk-native trails at
  76.0k; Jetty is the slowest in both native (46.5k) and adapter (64.2k) form.
- **Best latency profile (concurrency 16)**: undertow-adapter is clearly the
  winner (p50 228μs, p99 676μs), ahead of jdk-native (p50 264μs) and
  freeway (p50 270μs but p99 2187μs).
- **Low concurrency (8)**: differences are small; undertow-native and
  jetty-adapter lead while Jetty native is slowest.
- **Jetty native vs adapter**: the native harness handler is consistently
  slower than the Freeway adapter in this run, which is counter-intuitive and
  likely reflects warm-up/JIT effects (each combination starts a fresh server
  and only 300 warmup requests) rather than a real overhead inversion.
- **Tail latency**: freeway's p99 grows quickly at concurrency 16 (2187μs)
  then improves at 32 (1081μs), showing higher variance between iterations;
  undertow keeps the most stable tail across all concurrency levels.

## Caveats

- Single development machine, one run per configuration; results are for
  relative comparison, not absolute capacity planning.
- Each combination starts a fresh server; the first iteration of every
  combination is consistently lower (JIT warm-up), so the median is used but
  still understates steady-state performance.
- Client and server share the same JVM/host, so scheduling noise affects all
  engines equally but not identically.

## Reproduce

```bash
mvn -f freeway-benchmark/pom.xml exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args="suite \
    --engines=freeway,jdk-native,undertow-native,undertow-adapter,jetty-native,jetty-adapter \
    --scenarios=ping --concurrency=8,16,32 \
    --requests=3000 --warmup=300 --runs=3 \
    --output=benchmark-1.3.6-SNAPSHOT.md"
```
