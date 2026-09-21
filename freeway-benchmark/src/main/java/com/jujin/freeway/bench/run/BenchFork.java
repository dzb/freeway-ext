/*
 * Copyright 2026 dzb
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jujin.freeway.bench.run;

import com.jujin.freeway.bench.harness.ServerHarness;

/**
 * Isolated benchmark entry point: one JVM per role. {@code bench.role=server} starts the engine and
 * waits, {@code bench.role=client} warms up and measures and prints one {@code RESULT} line per
 * measured round, and the default role orchestrates one resident server plus one client that sends
 * {@code bench.warmup} warmup requests and then measures {@code bench.runs} rounds. Nothing is
 * restarted between rounds, so the server JIT stays warm.
 *
 * <p>The class only owns role dispatch and the two subprocess entry points; the run loop lives in
 * {@link ForkedRunner} and every process/classpath detail in {@link BenchProcesses}.
 *
 * <pre>
 * mvn -f freeway-benchmark/pom.xml -am process-classes
 * mvn -f freeway-benchmark/pom.xml exec:java \
 *   -Dexec.mainClass=com.jujin.freeway.bench.run.BenchFork \
 *   -Dbench.engine=freeway -Dbench.mode=keepalive \
 *   -Dbench.requests=20000 -Dbench.concurrency=32 -Dbench.runs=3
 * </pre>
 */
public final class BenchFork {

  private BenchFork() {}

  public static void main(String[] args) throws Exception {
    String role = BenchProcesses.prop("bench.role", "suite");
    if ("server".equalsIgnoreCase(role)) {
      runServer(
          BenchProcesses.prop("bench.engine", "freeway"),
          BenchProcesses.prop("bench.mode", "keepalive"));
      return;
    }
    if ("client".equalsIgnoreCase(role)) {
      runClient(
          BenchProcesses.prop("bench.engine", "freeway"),
          BenchProcesses.prop("bench.mode", "keepalive"),
          BenchProcesses.intProp("bench.port", 0),
          BenchProcesses.intProp("bench.requests", 2000),
          BenchProcesses.intProp("bench.concurrency", 2),
          BenchProcesses.intProp("bench.warmup", 200),
          BenchProcesses.intProp("bench.runs", 5),
          BenchProcesses.intProp("bench.pauseMillis", 3000));
      return;
    }

    ForkedRunner.run(
        BenchProcesses.prop("bench.engine", "freeway"),
        BenchProcesses.prop("bench.mode", "keepalive"),
        BenchProcesses.intProp("bench.requests", 20_000),
        BenchProcesses.intProp(
            "bench.concurrency", Math.max(1, Runtime.getRuntime().availableProcessors())),
        BenchProcesses.intProp("bench.warmup", 2_000),
        BenchProcesses.intProp("bench.runs", 3),
        BenchProcesses.intProp("bench.pauseMillis", 3000));
  }

  // --- server / client subprocess entry points ---

  private static void runServer(String engine, String mode) throws Exception {
    var eng = ServerHarness.Engine.fromString(engine);
    var scn = BenchMode.of(mode).scenario();
    try (var h = ServerHarness.start(eng, scn)) {
      System.out.println("READY port=" + h.port() + " engine=" + engine + " scenario=" + scn);
      System.out.flush();
      Thread.sleep(Long.MAX_VALUE);
    }
  }

  private static void runClient(
      String engine,
      String mode,
      int port,
      int requests,
      int concurrency,
      int warmup,
      int runs,
      int pauseMillis)
      throws Exception {
    var benchMode = BenchMode.of(mode);
    if (warmup > 0) {
      // Warm up once (this round's result is discarded), then let the server settle.
      BenchRunner.run(
          port, concurrency, requests, warmup, benchMode.scenario(), benchMode.clientMode());
      if (pauseMillis > 0) {
        Thread.sleep(pauseMillis);
      }
    }
    for (int i = 0; i < runs; i++) {
      var ir =
          BenchRunner.run(
              port, concurrency, requests, 0, benchMode.scenario(), benchMode.clientMode());
      System.out.println("RESULT " + ForkedRunner.toResult(engine, mode, requests, ir));
      if (i + 1 < runs && pauseMillis > 0) {
        Thread.sleep(pauseMillis);
      }
    }
  }
}
