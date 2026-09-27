#!/bin/bash
# One-command benchmark reproduction: environment lock, JMH isolated hot paths,
# black-box split matrix. Usage: ./repro.sh [--dry-run]
# Full run takes ~1h machine time on a quiet box; --dry-run prints every
# command without executing (verifies the script itself in seconds).
set -euo pipefail
cd "$(dirname "$0")"

CP="target/classes:$(cat target/benchmark.classpath)"
CONC=32
REQS=20000
WARM=20000
RUNS=8
DRY=""

for arg in "$@"; do
  if [ "$arg" = "--dry-run" ]; then DRY="echo DRY-RUN:"; fi
done

echo "=== environment lock ==="
git -C ../../freeway rev-parse HEAD 2>/dev/null || echo "core: n/a"
git rev-parse HEAD
echo "dirty: $(git status --short | wc -l) files"
java -version 2>&1 | head -n 1
grep -m1 "model name" /proc/cpuinfo
nproc
free -g | head -n 2

echo "=== JMH (protocol flags from annotations; json beside the report) ==="
for bench in \
  com.jujin.freeway.bench.jmh.RouteIndexBenchmark \
  com.jujin.freeway.bench.jmh.JsonCodecBenchmark \
  com.jujin.freeway.bench.jmh.MultipartFormBenchmark \
  com.jujin.freeway.http.engine.FilterChainBenchmark \
  com.jujin.freeway.http.engine.Http1xParserBenchmark \
  com.jujin.freeway.http.engine.HttpContextLookupBenchmark \
  com.jujin.freeway.http.engine.HttpContextOutputBenchmark \
  com.jujin.freeway.http.engine.ws.WebSocketFrameBenchmark; do
  simple="${bench##*.}"
  $DRY java -cp "$CP" org.openjdk.jmh.Main \
    -rf json -rff "../docs/benchmark-$(date +%F)-${simple}.json" "$bench"
done

echo "=== black-box split matrix (one resident server + client per cell, via suite --fork) ==="
$DRY taskset -c 0-7 java -cp "$CP" com.jujin.freeway.bench.BenchApp suite \
  --engines=freeway,freeway-native,jdk-native,robaho-native,undertow-native,undertow-vt,undertow-adapter,jetty-native,jetty-vt,jetty-adapter \
  --scenarios=ping,json \
  --concurrency=$CONC --requests=$REQS --warmup=$WARM --runs=$RUNS \
  --pause-millis=500 --median-last=5 --taskset-server=0-7 --taskset-client=8-15 --fork
$DRY taskset -c 0-7 java -cp "$CP" com.jujin.freeway.bench.BenchApp suite \
  --engines=freeway,undertow-native,undertow-vt,jetty-native,jetty-vt \
  --scenarios=ws_echo --mode=ws \
  --concurrency=$CONC --requests=$REQS --warmup=$WARM --runs=$RUNS \
  --pause-millis=500 --median-last=5 --taskset-server=0-7 --taskset-client=8-15 --fork
echo "REPRO_COMPLETE"
