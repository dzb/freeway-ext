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
 * The forked run loop: one engine, one resident server, one client. The client sends {@code warmup}
 * warmup requests (result discarded) and then measures {@code runs} rounds against the same server,
 * so the server JIT is warm for every measured round. This class owns the child processes and the
 * median summary; {@link BenchProcesses} owns every process/classpath detail.
 */
final class ForkedRunner {

  private ForkedRunner() {}

  /**
   * Runs one engine in one resident server plus one client, and prints every measured round plus
   * the median.
   */
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
        "=== %s mode=%s requests=%d concurrency=%d warmup=%d runs=%d pause=%dms ===%n",
        engine, mode, requests, concurrency, warmup, runs, pauseMillis);

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
                  "-Dbench.runs=" + runs,
                  "-Dbench.pauseMillis=" + pauseMillis,
                  BenchFork.class.getName())
              .redirectErrorStream(true)
              .redirectOutput(clientLog.toFile())
              .start();
      try {
        int exit = client.waitFor();
        if (exit != 0) {
          throw new RuntimeException(
              "Client exit " + exit + "\n" + BenchProcesses.readAllSafe(clientLog));
        }
        List<Result> results = parseResults(BenchProcesses.readAllSafe(clientLog));
        for (int i = 0; i < results.size(); i++) {
          System.out.printf("[run %d/%d] %s%n", i + 1, results.size(), results.get(i));
        }
        if (results.size() > 1) {
          System.out.printf("[median] %s%n", Result.median(results));
        }
      } finally {
        client.destroyForcibly();
        BenchProcesses.deleteSafe(clientLog);
      }
    } finally {
      server.destroyForcibly();
      BenchProcesses.deleteSafe(serverLog);
    }
  }

  private static List<Result> parseResults(String output) {
    List<Result> results = new ArrayList<>();
    for (String line : output.lines().toList()) {
      if (line.startsWith("RESULT ")) {
        results.add(Result.fromLine(line));
      }
    }
    if (results.isEmpty()) {
      throw new RuntimeException("No RESULT line in output:\n" + output);
    }
    return results;
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
