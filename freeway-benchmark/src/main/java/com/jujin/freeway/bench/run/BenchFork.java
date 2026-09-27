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
    String mode = BenchProcesses.prop("bench.mode", "keepalive");
    // Explicit scenario wins; absent, the mode decides (keepalive/short ride ping, ws rides echo).
    String scenarioProp = BenchProcesses.prop("bench.scenario", "");
    if ("server".equalsIgnoreCase(role)) {
      runServer(
          BenchProcesses.prop("bench.engine", "freeway"), mode, scenarioOf(scenarioProp, mode));
      return;
    }
    if ("client".equalsIgnoreCase(role)) {
      runClient(
          BenchProcesses.prop("bench.engine", "freeway"),
          mode,
          scenarioOf(scenarioProp, mode),
          BenchProcesses.intProp("bench.port", 0),
          BenchProcesses.intProp("bench.requests", 2000),
          BenchProcesses.intProp("bench.concurrency", 2),
          BenchProcesses.intProp("bench.warmup", 200),
          BenchProcesses.intProp("bench.runs", 5),
          BenchProcesses.intProp("bench.pauseMillis", 3000));
      return;
    }

    ForkedRunner.run(
        new ForkedRunner.Options(
            BenchProcesses.prop("bench.engine", "freeway"),
            scenarioOf(scenarioProp, mode).name().toLowerCase(java.util.Locale.ROOT),
            mode,
            BenchProcesses.intProp("bench.requests", 20_000),
            BenchProcesses.intProp(
                "bench.concurrency", Math.max(1, Runtime.getRuntime().availableProcessors())),
            BenchProcesses.intProp("bench.warmup", 2_000),
            BenchProcesses.intProp("bench.runs", 3),
            BenchProcesses.intProp("bench.pauseMillis", 3000),
            BenchProcesses.intProp("bench.medianLast", 0),
            BenchProcesses.prop("bench.taskset.server", ""),
            BenchProcesses.prop("bench.taskset.client", "")));
  }

  private static ServerHarness.Scenario scenarioOf(String scenarioProp, String mode) {
    if (scenarioProp != null && !scenarioProp.isBlank()) {
      return ServerHarness.Scenario.valueOf(scenarioProp.trim().toUpperCase(java.util.Locale.ROOT));
    }
    return BenchMode.of(mode).scenario();
  }

  // --- server / client subprocess entry points ---

  private static void runServer(String engine, String mode, ServerHarness.Scenario scn)
      throws Exception {
    var eng = ServerHarness.Engine.fromString(engine);
    try (var h = ServerHarness.start(eng, scn)) {
      // The effective XNIO sizing is part of the run's identity; quote it where it applies so a
      // comparison can be checked against a single value instead of guessing the default.
      String sizing = eng.xnio() ? " io-threads=" + ServerHarness.ioThreads() : "";
      System.out.println(
          "READY port=" + h.port() + " engine=" + engine + " scenario=" + scn + sizing);
      System.out.flush();
      Thread.sleep(Long.MAX_VALUE);
    }
  }

  private static void runClient(
      String engine,
      String mode,
      ServerHarness.Scenario scenario,
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
      BenchRunner.run(port, concurrency, requests, warmup, scenario, benchMode.clientMode());
      if (pauseMillis > 0) {
        Thread.sleep(pauseMillis);
      }
    }
    for (int i = 0; i < runs; i++) {
      var ir = BenchRunner.run(port, concurrency, requests, 0, scenario, benchMode.clientMode());
      System.out.println("RESULT " + ForkedRunner.toResult(engine, mode, requests, ir));
      if (i + 1 < runs && pauseMillis > 0) {
        Thread.sleep(pauseMillis);
      }
    }
  }
}
