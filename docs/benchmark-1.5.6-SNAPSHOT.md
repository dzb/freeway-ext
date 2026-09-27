# Benchmark: 1.5.6-SNAPSHOT worktree baseline (2026-09-26)

Protocol: `freeway-benchmark/BENCHMARK_PROTOCOL.md` (JMH defaults from annotations:
forks 2, warmup 5x1s, measurement 5x1s, thrpt, one class per invocation, fresh JVM
per run; black-box warmup + runs with median).

## Environment lock

- core HEAD: `68fc9325` (freeway), ext HEAD: `70b7cb4` (freeway-ext)
- worktree dirty at run time: core 38 files, ext 7 files — an uncommitted session's work (the
  `AsyncCarrier`/`KnownKeys` items recorded in the core CHANGELOG, among others). Those counts are
  what the run measured, not the tree that lands: read the revision as "the v1.5.5 commits plus
  local work", not as a reproducible commit pair.
- JDK: OpenJDK 25.0.4.1 (2026-08-18, 64-Bit Server VM, default flags, no tuning)
- OS: Ubuntu 26.04.1 LTS
- CPU: AMD Eng Sample 100-000000829-50_Y, 16 threads
- RAM: 29 GB
- JMH invocation: `java -cp target/classes:$(cat target/benchmark.classpath)
  org.openjdk.jmh.Main <one class>` (the `exec:java` fork path drops `ForkedMain`)
- Black-box: `bench suite --fork` (one resident server plus one client per cell;
  early rounds used the equivalent manual `serve` + `run --port` split, same
  `ServerHarness`/`BenchRunner` primitives underneath). v2 parameters: 32 concurrency,
  20000 requests/round, 20000 warmup, 8 rounds, median of last 5 (`--median-last=5`),
  taskset-split cores.

## JMH isolated hot paths (thrpt, ops/s; Cnt 10 = 2 forks x 5 iterations)

| Benchmark | Score | Error (99.9%) | Units |
|---|---|---|---|
| RouteIndex.exactMatch | 35,041,128 | 262,791 | ops/s |
| RouteIndex.paramMatch | 6,624,072 | 36,279 | ops/s |
| RouteIndex.wildcardMatch | 3,240,991 | 24,522 | ops/s |
| JsonCodec.fromJsonSmall | 9,817,017 | 728,507 | ops/s |
| JsonCodec.fromJsonMedium | 2,186,015 | 342,988 | ops/s |
| JsonCodec.fromJsonLarge | 36,316 | 664 | ops/s |
| JsonCodec.toJsonSmall | 21,237,453 | 607,200 | ops/s |
| JsonCodec.toJsonMedium | 4,193,113 | 63,763 | ops/s |
| JsonCodec.toJsonLarge | 33,904 | 1,575 | ops/s |
| MultipartForm.parse | 735,221 | 36,568 | ops/s |
| FilterChain.corsPreflight | 26,147,515 | 902,361 | ops/s |
| FilterChain.healthCheckMatch | 8,230,079 | 414,213 | ops/s |
| FilterChain.normalRequest | 9,982,878 | 418,095 | ops/s |
| Http1xParser.parse | 1,202,636 | 66,729 | ops/s |
| HttpContextLookup.headerExactMatch | 57,824,122 | 2,153,082 | ops/s |
| HttpContextLookup.headerCaseInsensitive | 56,486,643 | 1,600,995 | ops/s |
| HttpContextLookup.headerMiss | 129,278,754 | 2,068,249 | ops/s |
| HttpContextLookup.paramLookup | 239,732,075 | 13,494,646 | ops/s |
| HttpContextLookup.queryParam | 150,530,543 | 54,001,348 | ops/s |
| HttpContextOutput.sendPongText | 1,581,411 | 89,685 | ops/s |
| HttpContextOutput.sendPongJson | 1,491,007 | 258,679 | ops/s |
| HttpContextOutput.sendJsonShortcut | 7,261,428 | 487,340 | ops/s |
| HttpContextOutput.sendNotFound | 2,152,816 | 86,299 | ops/s |
| HttpContextOutput.readBodyThenOutput | 1,529,549,169 | 58,910,765 | ops/s |
| WebSocketFrame.constructSmallText | 230,995,390 | 20,485,267 | ops/s |
| WebSocketFrame.constructLargeText | 2,109,763 | 159,248 | ops/s |
| WebSocketFrame.readSmallText | 22,618,211 | 1,354,707 | ops/s |
| WebSocketFrame.readLargeText | 1,113,132 | 36,786 | ops/s |
| WebSocketFrame.readBinary | 40,157,506 | 4,081,821 | ops/s |
| WebSocketFrame.writeSmallText | 923,687,266 | 27,522,377 | ops/s |
| WebSocketFrame.writeLargeText | 939,982,671 | 207,579,989 | ops/s |

## HTTP black-box, split shape, v2 protocol (2026-09-26, `--median-last=5`)

`bench suite --fork` (server cores 0-7, client 8-15 via `--taskset-server/client`,
separate JVMs); 32 concurrency, 20000 requests/round, **20000 warmup, 8 rounds,
median of last 5** (`--median-last=5`). Zero errors everywhere. v1 numbers (warmup 5000, 5 rounds,
manual split) are superseded — rounds were still climbing then; the table below
is the standing record.

| Engine | ping req/s | json req/s | ws_echo req/s |
|---|---|---|---|
| freeway (built-in) | 375,500 | 436,600 | 62,700 |
| freeway-native | 433,200 | 423,200 | — |
| jdk-native | 306,100 | 323,500 | — |
| robaho-native | 512,800 | 488,700 | — |
| undertow-native | 480,600 | 528,900 | 64,700 |
| undertow-vt | 476,400 | 541,600 | 64,600 |
| undertow-adapter | 242,500 | 224,400 | — |
| jetty-native | 437,500 | 435,100 | 51,700 |
| jetty-vt | 439,600 | 460,000 | 56,500 |
| jetty-adapter | 290,600 | 280,000 | — |

p50/p95/p99 of the median round: freeway ping 52/163/656μs, json 54/113/369μs;
jetty-native json 53/112/250μs (best tails); WS ~130/330/600μs everywhere.

Reading:

- Natives (robaho/undertow/jetty) lead at ~435–565k; freeway built-in sits
  mid-pack (~375–435k, above jdk-native and both adapters); adapters hold
  35–50% of their natives — the seam tax is stable across protocols.
- `freeway-native` (same engine, hand-written handler, no routes/filters/codec)
  isolates the pipeline cost: ping +15% over built-in (375k→433k, now in the
  native band beside jetty-native), json flat (serialization dominates there).
  A back-to-back AB later flipped the json order (native 438.2k vs built-in
  401.5k, +9%) — same lesson as WS: within noise, the two are tied; only the
  ping-side pipeline cost (+15%) reproduces.
- The freeway ping/json gap is **6% at steady state** (was 31% under-warmed) —
  the anomaly is closed as transient compilation tax, not a steady cost. WS is
  flat from round one on every engine (frame-bound, no JIT ramp to speak of).
- Absolute levels roughly doubled vs the v1 round on longer warmup — the
  methodology lesson, quantified: short rounds measure warmup, not engines.
- WS is a three-way tie at ~63–65k (freeway/undertow-native/undertow-vt, inside
  noise), jetty trails at ~52–57k — frame-bound everywhere, flat from round one.

## Startup (5 cold JVMs, single empty module)

- Framework time (`Started freeway application in`): 70/82/74/79/77 ms → median **77 ms**
- Wall incl. JVM launch: 142/156/146/147/149 ms → median **147 ms**

A 7-module HTTP app (point-bbs boot test) reports the same framework line at
~65 ms — same order, so the framework's own startup cost dominates, not module
count at this scale.

## Appendix: JSON-gap fix attempt (negative result, reverted)

async-profiler pinned the under-warmed ping/json delta inside `outputJson`: ~86% of it
under `toJson→writeBean→read→invokeWithArguments` (runtime LambdaForm/spread machinery
visible as `spreadInvoker`/`MethodType.*`/`LambdaFormEditor` frames). The fix
adapted every bean read/write/ctor handle once at plan build
(`asType`/`asSpreader`) and switched use sites to `invokeExact` — full suite
green (410 commons + all downstream), mechanism sound.

It did not survive measurement, so it was reverted. The v2 table above is the
verdict: at steady state the gap is 6%, i.e. the disease was transient and the
cure targeted a cost steady state erases. Intermediate step-1 numbers (same box,
client flags `-Xms2g -Xmx2g -XX:+UseParallelGC`, medians of all 8: ping 376.4k,
json 263.4k) differ from the v2 table — same lesson a third way: absolutes drift
run to run on this box; only within-run rankings and the method above travel.

Measured evidence, both directions:

- JMH steady state, twice: toJsonSmall 21.2M → 18.4M/19.4M, fromJsonSmall
  9.8M → 8.0M/8.9M (directionally down, inside a noisy box).
- Black-box AB, same box back-to-back: pre-fix json 141.9k vs post-fix
  ~125–141k; pre-fix ping 175.1k vs same-code matrix 256k — box drift (±40%)
  dwarfs any effect, and the direction is flat-to-down, never up.

Lessons, kept: (1) the spread-pathology analysis stands (per-request LambdaForm
work is real, just small in steady state); (2) JMH-with-full-warmup cannot see
transient compilation taxes — it measures the state where the disease is
already cured; (3) on this box, no sub-30% effect is measurable end-to-end —
the next attempt needs a quieter box or a noise-immune probe (`-prof gc`
allocation counting: `invokeWithArguments` allocates per call, `invokeExact`
does not).
