# Freeway Ext JMH A/B — core 1.5.3 → 1.5.5-SNAPSHOT (convergence regression check)

**Date**: 2026-09-22 (main A/B) · 2026-09-23 (reversed re-test)
**Question**: did the core convergence series (WebServer→HttpServer assembly,
HttpContext two-half split / RequestView, in-chain error mapping, mime table into
`MediaTypes`, …) regress the freeway-http hot paths?
**Protocol**: [BENCHMARK_PROTOCOL.md](../freeway-benchmark/BENCHMARK_PROTOCOL.md) §3 —
the annotated defaults on every class: 2 forks, 5×1s warmup, 5×1s measurement,
`thrpt`, identical options on both sides, fresh JVM per benchmark. Classes were run
one at a time, interleaved baseline→candidate (09-22) and candidate→baseline (09-23).
**Significance**: JMH 95% CI half-width (`scoreError`) non-overlap, plus the protocol's
3% noise floor. CI overlap ⇒ treated as noise even when the point estimate moves.

## Environment

| Item | Value |
| --- | --- |
| JDK | OpenJDK 25.0.4.1 (Ubuntu build 25.0.4.1+1-1-26.04.4-Ubuntu) |
| OS | Ubuntu 26.04.1 LTS |
| CPU | AMD Eng Sample: 100-000000829-50_Y, 16 threads, L3 16 MiB |
| RAM | 29 GiB |
| Governor | `powersave` (desktop session: gnome-shell, chrome, FlClash present) |
| JVM flags | none — JMH forks run plain (`VM options: <none>` recorded in logs) |

> Caveat (protocol §2): this is a noisy desktop, not a locked benchmark host.
> All A/B pairs below were run in a single session minutes apart, interleaved
> per class, which is what makes the comparison valid — not the absolute numbers.

## Builds compared

| Side | freeway-ext | freeway core | Core jars on the classpath |
| --- | --- | --- | --- |
| Baseline | `1489e17` | **v1.5.3** (`65686fb4`, released 2026-09-20) | release jars from `~/.m2` |
| Candidate | `6cf50f2` | `6499e453` (`v1.5.3-19`, converged) | `1.5.5-SNAPSHOT` installed 2026-09-22 20:25–20:39 |

Provenance notes:

- The baseline worktree (`/tmp/opencode/freeway-ext-baseline` at `1489e17`, built
  offline against the 1.5.3 release) pins `freeway.version=1.5.3` — the same core the
  pre-convergence black-box baselines used. Neither build shares a version coordinate,
  so neither side can silently resolve the other's jars.
- **`freeway-commons` is byte-identical between the two builds**:
  `git diff 65686fb4 6499e453 -- freeway-commons` touches only `pom.xml`.
- Between the main A/B and the 09-23 re-test, `~/.m2`'s `1.5.5-SNAPSHOT` http/ioc/boot/db
  jars were reinstalled (2026-09-23 00:34) from a working tree **between** `6499e453`
  and the 00:49 commit batch (`allowedMethods` rename not yet present). The re-test's
  candidate side therefore serves the http-touching classes from that build; its
  JsonCodec side is unaffected (commons jar untouched since 09-22 20:25). Core HEAD at
  report time: `dd43d40f`.

## Result — main A/B (2026-09-22, 21:05–21:21)

23 benchmark methods, throughput in ops/s, baseline first in every class pair.

| Benchmark | v1.5.3 | 1.5.5 (cand) | Δ | Verdict |
| --- | ---: | ---: | ---: | --- |
| **FilterChainBenchmark** | | | | |
| `corsPreflight` | 26.2M ±0.5M | 26.5M ±0.8M | +1.0% | ~ noise |
| `healthCheckMatch` | 8.35M ±0.15M | 8.69M ±0.12M | **+4.1%** | ▲ |
| `normalRequest` | 10.4M ±0.12M | 10.6M ±0.16M | +1.7% | ~ noise |
| **Http1xParserBenchmark** | | | | |
| `parse` | 1.27M ±9k | 1.29M ±46k | +1.6% | ~ noise |
| **HttpContextLookupBenchmark** | | | | |
| `headerCaseInsensitive` | 57.4M ±1.3M | 57.1M ±0.7M | −0.5% | ~ noise |
| `headerExactMatch` | 59.8M ±3.4M | 62.4M ±0.8M | +4.4% | ~ noise (CI overlap) |
| `headerMiss` | 129M ±1.7M | 130M ±0.4M | +0.6% | ~ noise |
| `paramLookup` | 254M ±6.9M | 256M ±7.0M | +0.6% | ~ noise |
| `queryParam` | 190M ±1.5M | 197M ±2.1M | **+3.6%** | ▲ |
| **HttpContextOutputBenchmark** | | | | |
| `readBodyThenOutput` | 1.56G ±17M | 1.56G ±16M | −0.0% | ~ noise |
| `sendJsonShortcut` | 7.59M ±0.12M | 7.62M ±0.11M | +0.3% | ~ noise |
| `sendNotFound` | 2.39M ±0.28M | 2.54M ±0.09M | +6.5% | ~ noise (CI overlap) |
| `sendPongJson` | 1.67M ±41k | 1.48M ±92k | **−11.4%** | ▼ |
| `sendPongText` | 1.84M ±0.29M | 1.81M ±0.25M | −1.6% | ~ noise |
| **RouteIndexBenchmark** | | | | |
| `exactMatch` | 35.2M ±0.23M | 35.2M ±0.50M | −0.1% | ~ noise |
| `paramMatch` | 6.73M ±0.14M | 6.74M ±0.11M | +0.2% | ~ noise |
| `wildcardMatch` | 3.23M ±76k | 3.22M ±0.11M | −0.2% | ~ noise |
| **JsonCodecBenchmark** | | | | |
| `fromJsonLarge` | 36.2k ±340 | 35.8k ±1.8k | −0.9% | ~ noise |
| `fromJsonMedium` | 2.14M ±69k | 1.69M ±0.34M | **−20.9%** | ▼ |
| `fromJsonSmall` | 9.68M ±0.25M | 8.14M ±2.0M | −15.9% | ~ noise (CI overlap) |
| `toJsonLarge` | 37.6k ±370 | 36.0k ±680 | **−4.2%** | ▼ |
| `toJsonMedium` | 4.39M ±44k | 3.77M ±0.83M | −14.1% | ~ noise (CI overlap) |
| `toJsonSmall` | 22.6M ±0.29M | 15.7M ±2.9M | **−30.5%** | ▼ |

Summary: **2 significant ▲, 4 significant ▼, 17 within noise.**

## Observations

### The converged http engine hot paths show no regression

Route matching (±0.2%), request parsing (+1.6%), header/query/param lookup
(−0.5%…+3.6%), the cors→health filter chain (+1.0%…+4.1%) and text/not-found
response output (−1.6%…+6.5%) are all flat or slightly up — two small but
CI-significant gains (`healthCheckMatch` +4.1%, `queryParam` +3.6%). Nothing in the
measured surface moved beyond the 3% noise floor in the regression direction.

Coverage of the convergence commits, stated honestly:

| Convergence change | Exercised by | Verdict |
| --- | --- | --- |
| HttpContext two-half split / RequestView shared read face | `HttpContextLookup*`, `FilterChain*`, `Http1xParser` | flat / ▲ |
| mime table into `MediaTypes` (`d730aeab`) | text & JSON output paths (`sendPongText` etc.) | flat |
| 404/error response shape | `sendNotFound` | flat (CI overlap, ▲ direction) |
| **in-chain error mapping (`3d9e239d`)** | **not covered by JMH** — it lives in `HttpServer`'s chain assembly (`HttpServer.java` only), and `FilterChainBenchmark` hand-builds cors→health→handler without the server wrapper | **unverified here** — black-box only, see context below |
| assembly / renames (`bdcc0d16`, `51726617`, …) | startup-only, not a per-request path | n/a |

### The JsonCodec "regression" is not credible as a code regression

All six `JsonCodecBenchmark` methods moved negative (4 CI-significant, down to
−30.5%), and `sendPongJson` (which serializes per request) moved −11.4% while its
text sibling `sendPongText` stayed flat. Taken at face value that would "explain"
the black-box json deficit. It does not survive scrutiny:

1. **The code is identical.** `freeway-commons` has zero source changes between
   v1.5.3 and `6499e453` (diff = `pom.xml` version line only). The benchmark source is
   also unchanged between `1489e17` and `6cf50f2`. Same source, same JDK ⇒ same bytecode.
2. **The same operation measured both directions in the same session.**
   `toJsonSmall` serializes `Map.of("status","ok")` through `JsonCodecDefault`:
   −30.5% ▼. `healthCheckMatch` ends in `ctx.sendJson(HttpStatus.OK, Map.of("status","ok"))`
   — the same codec call on the same payload, plus output writing: **+4.1% ▲**.
   A codec that became 30% slower cannot simultaneously power a 4% faster benchmark.
3. **JMH's CI quantifies iteration noise inside one invocation, not systematic bias
   between invocations.** The candidate side of `JsonCodecBenchmark` was the last
   invocation of a 16-minute session on a `powersave` governor; between-invocation
   drift (frequency/thermal) is invisible to the reported CIs.

So the ▼ cluster was attributed to between-invocation measurement bias, and the
reversed-order re-test below confirmed exactly that.

### Context: yesterday's black-box runs (reference only)

`bench.db` #41–#47 (2026-09-22, same protocol parameters as the stored pre-convergence
baselines) showed cross-session swings of +40…108% for the *unchanged* `jdk-native`
control — i.e. the session effect dwarfs any conclusion the absolute numbers could
carry; normalized freeway-vs-control ratios pointed at a json-path deficit (−44…−55%),
which is what motivated this JMH A/B. Per protocol §1/§5 those black-box numbers are
reference only; the JMH comparison above is the decision-grade input.

## Re-test — reversed order (2026-09-23)

Yesterday's ordering ran baseline→candidate for every class. The re-test swapped the
order (candidate first) for the two disputed classes on a fresh session/day:

| Benchmark | candidate-first | baseline-second | Δ | Verdict |
| --- | ---: | ---: | ---: | --- |
| `JsonCodecBenchmark.toJsonSmall` | 21.7M ±0.21M | 21.7M ±0.21M | +0.2% | ~ noise (was −30.5% ▼) |
| `JsonCodecBenchmark.toJsonMedium` | 4.25M ±74k | 4.24M ±34k | +0.2% | ~ noise (was −14.1%) |
| `JsonCodecBenchmark.toJsonLarge` | 36.2k ±210 | 36.1k ±750 | +0.3% | ~ noise (was −4.2% ▼) |
| `JsonCodecBenchmark.fromJsonSmall` | 9.80M ±150k | 9.79M ±0.20M | +0.1% | ~ noise (was −15.9%) |
| `JsonCodecBenchmark.fromJsonMedium` | 2.28M ±40k | 2.05M ±57k | **+11.6%** | ▲ (was −20.9% ▼) |
| `JsonCodecBenchmark.fromJsonLarge` | 36.9k ±640 | 35.9k ±770 | +3.0% | ~ noise (was −0.9%) |
| `HttpContextOutputBenchmark.sendPongJson` | 1.42M ±81k | 1.42M ±0.14M | −0.5% | ~ noise (was −11.4% ▼) |
| `HttpContextOutputBenchmark.sendPongText` | 1.85M ±78k | 1.79M ±0.18M | +3.1% | ~ noise (was −1.6%) |
| `HttpContextOutputBenchmark.sendNotFound` | 2.59M ±59k | 2.40M ±0.38M | +8.0% | ~ noise (CI overlap) |
| `HttpContextOutputBenchmark.sendJsonShortcut` | 7.63M ±66k | 7.53M ±82k | +1.3% | ~ noise |
| `HttpContextOutputBenchmark.readBodyThenOutput` | 1.57G ±13M | 1.57G ±21M | +0.2% | ~ noise |

Re-test summary: **0 significant ▼, 1 significant ▲, 10 within noise.**

Interpretation (the rule fixed in advance): **Flip.** Every method that was ▼
yesterday measured flat or ▲ today when its side ran first — `fromJsonMedium` even
swung from −20.9% ▼ to +11.6% ▲. All four original deficits, plus `sendPongJson`
−11.4%, vanished.

**Position bias confirmed**: on this host, whoever runs *first* in a class pair
measured faster — baseline yesterday (first) beat candidate; candidate today (first)
beat baseline. The bias reached −20…−30% on small-payload codec methods while leaving
neighboring methods flat, and JMH's within-invocation CI cannot see it.

Consequences for method (protocol §3/§5):

- A single A→B pass is **not decision-grade on this machine** for invocations this
  short and frequency-sensitive; a comparison must run both orders (A→B then B→A) or
  A→B→A and take the order-robust direction.
- Yesterday's *flat* results remain conservative: the candidate ran second everywhere,
  i.e. against the bias — its true position-neutral numbers are at least what was
  measured.

## Verdict

**The convergence did not regress freeway-http.** Across both orders — 34 measured
method-comparisons in total — there is no reproducible significant deficit on the
candidate side:

- Engine hot paths (route, parse, lookup, filter chain, text/not-found/JSON-shortcut
  output): flat, with two small CI-significant gains (`healthCheckMatch` +4.1%,
  `queryParam` +3.6%).
- The apparent json regression (JsonCodec cluster + `sendPongJson`) was an
  order-dependent measurement artifact: it disappears, and partly inverts, when the
  candidate runs first. `freeway-commons` was byte-identical throughout, so no code
  regression existed to find.
- The one convergence change JMH does not cover — in-chain error mapping
  (`3d9e239d`, lives in `HttpServer`'s assembly) — remains unverified by
  microbenchmark; the black-box evidence for it is noise-tainted and only serves as
  a pointer, not proof. A chain-assembly-level JMH fixture would close this gap.

## Reproduce

```bash
# baseline side (worktree pinned to the pre-convergence ext, core 1.5.3 release jars)
git worktree add /tmp/opencode/freeway-ext-baseline 1489e17
mvn -o -f /tmp/opencode/freeway-ext-baseline/freeway-benchmark/pom.xml -DskipTests process-classes

# candidate side (main tree, core 1.5.5-SNAPSHOT installed from ../freeway)
mvn -o -f freeway-benchmark/pom.xml -DskipTests process-classes

# per class, one invocation per side, protocol defaults come from the annotations:
java -cp "$(cat <side>/freeway-benchmark/target/benchmark.classpath):<side>/freeway-benchmark/target/classes" \
  org.openjdk.jmh.Main com.jujin.freeway.http.engine.FilterChainBenchmark \
  -rf json -rff /tmp/opencode/jmh-<side>-<Class>.json

# summarize (95% CI overlap + 3% protocol floor)
python3 /tmp/opencode/summarize-jmh.py jmh
```

Run scripts as executed: `/tmp/opencode/run-jmh-ab.sh` (interleaved, baseline first)
and `/tmp/opencode/run-jmh-retest.sh` (reversed). Raw JMH logs and JSON results are
kept in `/tmp/opencode/jmh-*.log|json` and `jmh2-*` for this machine only.
