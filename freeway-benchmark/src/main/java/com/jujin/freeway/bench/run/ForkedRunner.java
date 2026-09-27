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
public final class ForkedRunner {

  private ForkedRunner() {}

  /**
   * The inputs of one forked cell. A record rather than eleven positional parameters: five of them
   * are {@code int}s and two are CPU ranges, so a transposed pair compiles and silently measures
   * something else. Defaults stay with the layers that own them — the CLI options, and the {@code
   * bench.*} properties the child is spawned with — never restated here.
   */
  public record Options(
      String engine,
      String scenario,
      String mode,
      int requests,
      int concurrency,
      int warmup,
      int runs,
      int pauseMillis,
      int medianLast,
      String tasksetServer,
      String tasksetClient) {}

  /**
   * Runs one engine/scenario in one resident server plus one client, prints every measured round
   * plus the median, and returns the rounds for persistence. Processes are always split — sharing a
   * JVM between server and load generator is the smoke shape.
   */
  public static List<Result> run(Options o) throws Exception {
    String engine = o.engine();
    String scenario = o.scenario();
    String mode = o.mode();
    int requests = o.requests();
    int concurrency = o.concurrency();
    int warmup = o.warmup();
    int runs = o.runs();
    int pauseMillis = o.pauseMillis();
    String tasksetServer = o.tasksetServer();
    String tasksetClient = o.tasksetClient();
    System.out.printf(
        "=== %s/%s mode=%s requests=%d concurrency=%d warmup=%d runs=%d pause=%dms ===%n",
        engine, scenario, mode, requests, concurrency, warmup, runs, pauseMillis);

    Path serverLog = Files.createTempFile("bench-server-", ".log");
    var serverCommand =
        new ArrayList<String>(
            List.of(BenchProcesses.javaBinary(), "-cp", BenchProcesses.classpath()));
    serverCommand.addAll(
        List.of(
            "-Dbench.role=server",
            "-Dbench.engine=" + engine,
            "-Dbench.scenario=" + scenario,
            "-Dbench.mode=" + mode,
            BenchFork.class.getName()));
    Process server =
        new ProcessBuilder(BenchProcesses.pinned(serverCommand, tasksetServer))
            .redirectErrorStream(true)
            .redirectOutput(serverLog.toFile())
            .start();
    try {
      int port = BenchProcesses.awaitReady(server, serverLog, Duration.ofSeconds(30));
      Path clientLog = Files.createTempFile("bench-client-", ".log");
      var clientCommand =
          new ArrayList<String>(
              List.of(BenchProcesses.javaBinary(), "-cp", BenchProcesses.classpath()));
      clientCommand.addAll(
          List.of(
              "-Dbench.role=client",
              "-Dbench.engine=" + engine,
              "-Dbench.scenario=" + scenario,
              "-Dbench.mode=" + mode,
              "-Dbench.port=" + port,
              "-Dbench.requests=" + requests,
              "-Dbench.concurrency=" + concurrency,
              "-Dbench.warmup=" + warmup,
              "-Dbench.runs=" + runs,
              "-Dbench.pauseMillis=" + pauseMillis,
              BenchFork.class.getName()));
      Process client =
          new ProcessBuilder(BenchProcesses.pinned(clientCommand, tasksetClient))
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
          // The window is named on the line: the suite persists the median-rps round of the same
          // window, and an unlabelled "median" next to a different one in the summary is a trap.
          int last = o.medianLast();
          String label =
              last > 0 && last < results.size()
                  ? "[median last " + last + " of " + results.size() + "]"
                  : "[median]";
          System.out.printf("%s %s%n", label, Result.median(results, last));
        }
        return results;
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
        ir.p99us(),
        ir.saturated());
  }
}
