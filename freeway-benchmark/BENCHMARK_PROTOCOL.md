# Freeway Benchmark Protocol

This protocol defines how benchmark results are collected and how they are used.
If a run does not follow this protocol, its numbers are for reference only and must
not be used for design decisions.

## 1. Scope

- `JMH` benchmarks measure isolated hot paths inside the JVM.
- Black-box HTTP benchmarks measure end-to-end request handling.
- The in-repo smoke harness is for local validation and regression detection.
- Final performance comparisons should use the separate-process HTTP benchmark
  or an external tool under the same workload, same commit range, and same protocol.

## 2. Environment Lock

Record the following for every benchmark report:

- commit SHA
- branch name
- JDK vendor and exact build
- OS version
- CPU model
- physical core count
- RAM size
- GC settings
- heap size and JVM flags
- benchmark module version

Keep these conditions stable across comparison runs:

- same machine
- same power mode
- no background load if it can be avoided
- same JDK build
- same JVM flags
- same input data
- same route set / same SQL / same payloads

## 3. JMH Protocol

Default JMH settings for freeway microbenchmarks:

- forks: 2
- warmup iterations: 5
- measurement iterations: 5
- warmup time: 1s
- measurement time: 1s
- mode: `thrpt`

Use `avgt` only when you are explicitly investigating latency-like cost per operation.
If the benchmark is allocation-sensitive, add `-prof gc`.

These defaults live on the benchmark classes themselves (`@BenchmarkMode`,
`@OutputTimeUnit`, `@Warmup`, `@Measurement`, `@Fork`), so a plain invocation with no
strategy options follows the protocol:

```bash
mvn -f freeway-benchmark/pom.xml -am -DskipTests exec:java \
  -Dexec.mainClass=org.openjdk.jmh.Main \
  -Dexec.args='com.jujin.freeway.bench.jmh.RouteIndexBenchmark'
```

Command-line options still override the annotations (`-f`, `-wi`, `-i`, `-bm`), which is how
local iteration is done. A run whose options differ from the block above does not follow this
protocol: its numbers are reference only, and the recorded options must say so.

Rules:

- run candidate and baseline with identical JMH options
- use a fresh JVM process for each run
- do not mix benchmark classes in a single ad hoc invocation unless the comparison requires it
- do not compare numbers collected with different forks, different warmup lengths, or different payloads

## 4. HTTP Black-Box Protocol

The benchmark used for design decisions should be run with:

- one resident server JVM per engine/mode
- one client JVM per engine/mode
- one fixed endpoint set
- one fixed request shape
- one fixed concurrency level
- a fixed count of warmup requests, discarded before measurement
- at least 3 measured rounds against that same server
- a pause between the warmup and each measured round (`bench.pauseMillis`)
- median summary across the measured rounds

For final comparisons, external tools such as `wrk2` for HTTP/1.1 and `h2load`
for HTTP/2/TLS are still preferred when available.

- treat the in-repo smoke harness as a correctness and local sanity check, not the last word on performance

Capture:

- throughput
- p50
- p95
- p99
- errors
- timeouts

## 5. Comparison Rules

- Use at least 3 measured rounds for a black-box comparison.
- Prefer the median round when summarizing.
- Report both absolute values and relative delta.
- If the change is below 3 percent, treat it as noise until it is reproduced.
- If p95 or p99 regresses by more than 5 percent, investigate before merging.

## 6. Reporting Template

```text
Benchmark:
Commit:
Machine:
JDK:
Flags:
Protocol:
Baseline:
Candidate:
Result:
Notes:
```

Suggested result table:

| Workload | Baseline | Candidate | Delta |
| --- | ---: | ---: | ---: |
| `Http1xParser` |  |  |  |
| `RouteIndex` |  |  |  |
| `MultipartForm` |  |  |  |
| `HTTP /ping` |  |  |  |
