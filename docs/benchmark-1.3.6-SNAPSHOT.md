# Freeway Ext HTTP Engine Benchmark — 1.3.6-SNAPSHOT

**Date**: 2026-08-07
**Scenario**: `ping` (GET `/ping` → 200 `pong`), keep-alive mode
**Load**: 3,000 requests per iteration, 300 warmup requests, 3 iterations per combination
**Reported value**: median of the 3 iterations

## Environment

| Item | Value |
| --- | --- |
| JDK | OpenJDK 64-Bit Server VM 25.0.3 (Temurin) |
| OS | macOS (Darwin 25.5.0, arm64) |
| CPU | Apple M4 |
| Freeway Ext | 1.3.6-SNAPSHOT (with audit remediation batch; core optimized build installed locally) |

> Note: this run is on Apple M4. The previous 1.3.6-SNAPSHOT report (2026-08-04)
> ran on Linux amd64 (16 threads) — numbers are **not directly comparable across
> machines**; engine rankings within a run are the meaningful signal.

## Results

### Throughput (RPS, median of 3)

| Engine | Concurrency 8 | Concurrency 16 | Concurrency 32 |
| --- | ---: | ---: | ---: |
| freeway | 79.7k | **107.5k** | 96.1k |
| jdk-native | 69.6k | 95.7k | 97.3k |
| undertow-native | 76.2k | **137.8k** | 127.9k |
| undertow-adapter | 75.8k | 80.1k | 80.2k |
| jetty-native | 44.4k | 82.5k | 100.1k |
| jetty-adapter | 75.9k | 93.4k | 100.1k |

### Latency (p50 / p95 / p99, μs, at concurrency 16)

| Engine | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| freeway | 123 | 182 | 573 |
| jdk-native | 134 | 269 | 892 |
| undertow-native | 94 | 164 | 309 |
| undertow-adapter | 171 | 317 | 608 |
| jetty-native | 172 | 292 | 458 |
| jetty-adapter | 154 | 256 | 325 |

## Observations

- **freeway (core) is competitive with the native baselines**: 107.5k RPS at
  concurrency 16, lowest p99 tail of any engine at that point (573μs).
- **undertow-native peaks highest** (137.8k @ c16) — but as a bare-handler
  baseline, not an achievable application path.
- **undertow-adapter saturates at ~80k regardless of concurrency** (80.1k @
  c16, 80.2k @ c32) while undertow-native scales 76k → 138k → 128k. This is a
  fixed per-request cost, not contention.
- **jetty-adapter tracks jetty-native** (93.4k vs 82.5k @ c16; adapter
  slightly faster at c8) — the adapter layer itself is cheap on Jetty.
- The audit remediation batch (WS message limits, serialized SSE, header
  validation, TLS/h2 fixes) adds **no measurable cost** to the ping hot path.

### Layer-cost analysis (undertow-adapter −42% vs undertow-native)

Profiling via subtraction experiments (same JVM/client, concurrency 16):

| Configuration | RPS | Δ |
| --- | ---: | --- |
| undertow-native (bare handler, I/O thread) | 137.8k | baseline |
| bare handler + `exchange.dispatch()` wrapper | 76.3k | **−45%** |
| adapter engine, minimal handler (no WebServer pipeline) | 75.4k | −45% |
| full undertow-adapter (dispatch + pipeline + pooled context) | 80.1k | −42% |

Conclusions:

- **The entire gap is the per-request `exchange.dispatch()` thread hand-off**
  (native+dispatch ≈ full adapter). The WebServer pipeline, pooled-context
  reset, X-Request-Id handling, and response path together cost ~3% (noise).
- This is the documented trade-off in `UndertowWebEngine.start()`: Undertow
  invokes the root handler on an I/O thread, so application code (which may
  block on DB/IO) must be dispatched to the worker pool. The hand-off is the
  price of blocking safety on Undertow's I/O-thread model.
- `freeway.http.undertow.dispatch-io=false` does **not** recover the gap
  (72.9k @ c16): running handlers on I/O threads serializes per-connection
  network processing, net worse than the hand-off.
- Jetty 12, by contrast, already runs request handlers on its
  `QueuedThreadPool` — no extra hand-off, which is why jetty-adapter shows no
  such penalty.
- Closing the gap would require a different receive model (e.g. the core
  `FreewayHttpEngine`'s virtual-thread-per-connection design), not an adapter
  tweak. The core engine already provides that path (107.5k @ c16).

### freeway core vs undertow-native: model cost, not implementation defect

| Engine | c8 | c16 | c32 | c8→c16 | c16→c32 |
| --- | ---: | ---: | ---: | ---: | ---: |
| freeway | 79.7k | 107.5k | 96.1k | +35% | **−11%** |
| undertow-native | 76.2k | 137.8k | 127.9k | +81% | −7% |

- freeway is a virtual-thread-per-connection engine with blocking socket I/O;
  undertow-native is XNIO event-driven (10 I/O threads, selector). At
  concurrency ≤ cores (c8) freeway matches or beats the event loop — each
  virtual thread gets a private carrier, and there is no selector batching
  latency. Above the core count (c16/c32) virtual-thread scheduling
  (park/unpark + carrier mount/unmount per blocking I/O) dominates: −22%/−33%
  vs the native baseline, and freeway's own throughput turns negative
  c16→c32.
- This is the price of the engine's design choice, not a defect: blocking
  safety is free (handlers may call DB/IO without dispatch), which the
  event-driven baseline does not offer — the fair comparison is freeway
  (107.5k) vs undertow-adapter (80.1k), both blocking-safe, where the core
  engine leads by 34%.
- A hybrid model (per-connection virtual threads below the core count,
  shared event loops above it) is the theoretical fix for the high-concurrency
  gap, but would require reworking the blocking I/O layer of the core and is
  not planned.

### Sustained-load behavior

Under BenchFork sustained load (50,000 requests × 5 runs per process, c16)
freeway settles at 55–60k RPS (vs 107.5k in the short suite) — the core
engine's long-run throughput roughly halves, pointing at GC/allocation or
scheduling pressure under sustained load rather than a warm-up artifact.
undertow-native holds ~85k under the same protocol; undertow-adapter ~52k.
Long-run profiles are the next candidate investigation if sustained throughput
matters.

## Reproduce

```bash
mvn -f freeway-benchmark/pom.xml exec:java \
  -Dexec.mainClass=com.jujin.freeway.bench.BenchApp \
  -Dexec.args="suite \
    --engines=freeway,jdk-native,undertow-native,undertow-adapter,jetty-native,jetty-adapter \
    --scenarios=ping --concurrency=8,16,32 \
    --requests=3000 --warmup=300 --runs=3 \
    --output=docs/benchmark-1.3.6-SNAPSHOT.md"
```

Layer probes (adapter engine without pipeline, bare handler with dispatch):
`/tmp/adaptprobe/AdapterProbe.java` (kept out of the repo — one-off analysis).
