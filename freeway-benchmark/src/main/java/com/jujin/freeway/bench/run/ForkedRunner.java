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

import com.jujin.freeway.bench.model.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite half of the forked benchmark: it owns the run loop, the pause between runs and the
 * median summary, and delegates every child-process detail to {@link BenchProcesses}. Splitting it
 * out of {@link BenchFork} keeps the entry class to role dispatch while the orchestration stays
 * readable and independently reviewable.
 */
final class ForkedRunner {

  private ForkedRunner() {}

  /** Runs {@code runs} isolated fork cycles for one engine/mode and prints each result. */
  static void run(
      String engine,
      String mode,
      int requests,
      int concurrency,
      int warmup,
      int runs,
      int pauseMillis)
      throws Exception {
    System.out.printf(
        "=== %s mode=%s requests=%d concurrency=%d runs=%d ===%n",
        engine, mode, requests, concurrency, runs);

    List<Result> results = new ArrayList<>();
    for (int i = 0; i < runs; i++) {
      Result r = runFork(engine, mode, requests, concurrency, warmup, i);
      results.add(r);
      System.out.printf("[run %d/%d] %s%n", i + 1, runs, r);
      if (i + 1 < runs && pauseMillis > 0) Thread.sleep(pauseMillis);
    }
    if (runs > 1) System.out.printf("[median] %s%n", Result.median(results));
  }

  // --- fork orchestration ---

  private static Result runFork(
      String engine, String mode, int requests, int concurrency, int warmup, int runIdx)
      throws Exception {
    Path serverLog = Files.createTempFile("bench-server-", ".log");
    Process server =
        new ProcessBuilder(
                BenchProcesses.javaBinary(),
                "-cp",
                BenchProcesses.classpath(),
                "-Dbench.role=server",
                "-Dbench.engine=" + engine,
                "-Dbench.mode=" + mode,
                BenchFork.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(serverLog.toFile())
            .start();
    try {
      int port = BenchProcesses.awaitReady(server, serverLog, Duration.ofSeconds(30));
      Path clientLog = Files.createTempFile("bench-client-", ".log");
      Process client =
          new ProcessBuilder(
                  BenchProcesses.javaBinary(),
                  "-cp",
                  BenchProcesses.classpath(),
                  "-Dbench.role=client",
                  "-Dbench.engine=" + engine,
                  "-Dbench.mode=" + mode,
                  "-Dbench.port=" + port,
                  "-Dbench.requests=" + requests,
                  "-Dbench.concurrency=" + concurrency,
                  "-Dbench.warmup=" + warmup,
                  BenchFork.class.getName())
              .redirectErrorStream(true)
              .redirectOutput(clientLog.toFile())
              .start();
      try {
        int exit = client.waitFor();
        if (exit != 0)
          throw new RuntimeException(
              "Client exit " + exit + "\n" + BenchProcesses.readAllSafe(clientLog));
        return parseResult(BenchProcesses.readAllSafe(clientLog));
      } finally {
        client.destroyForcibly();
        BenchProcesses.deleteSafe(clientLog);
      }
    } finally {
      server.destroyForcibly();
      BenchProcesses.deleteSafe(serverLog);
    }
  }

  private static Result parseResult(String output) {
    for (String line : output.lines().toList()) {
      if (line.startsWith("RESULT ")) return Result.fromLine(line);
    }
    throw new RuntimeException("No RESULT line in output:\n" + output);
  }

  static Result toResult(String engine, String mode, int requests, BenchRunner.IterationResult ir) {
    return new Result(
        engine,
        mode,
        requests,
        requests - ir.errors(),
        ir.errors(),
        ir.rps(),
        ir.p50us(),
        ir.p95us(),
        ir.p99us());
  }
}
