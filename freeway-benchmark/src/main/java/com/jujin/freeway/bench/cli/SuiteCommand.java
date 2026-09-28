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

package com.jujin.freeway.bench.cli;

import com.jujin.freeway.bench.db.BenchRepository;
import com.jujin.freeway.bench.harness.ScenarioSpec;
import com.jujin.freeway.bench.harness.ServerHarness;
import com.jujin.freeway.bench.run.BenchMode;
import com.jujin.freeway.bench.run.BenchRunner;
import com.jujin.freeway.bench.run.ForkedRunner;
import com.jujin.freeway.ioc.event.EventBus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code bench suite} — runs multiple benchmark combinations and generates a comparison report.
 *
 * <p>Arguments:
 *
 * <pre>
 * --engines=freeway,robaho-native,undertow-native
 * --scenarios=ping,json
 * --concurrency=8,16
 * --requests=2000
 * --warmup=200
 * --runs=3
 * --mode=keepalive      connection mode: keepalive, short, or ws
 * --median-last=0       representative round = median of the last N rounds (0 = all)
 * --pause-millis=0      pause between rounds (fork shape only; the in-JVM shape ignores it)
 * --fork                run each cell as resident server + client in separate JVMs
 * --taskset-server=     CPU range for the server process  (fork shape only)
 * --taskset-client=     CPU range for the client process  (fork shape only)
 * --output=report.md    optional: write Markdown report to file
 * </pre>
 *
 * <p>Each combination of (engine, scenario, concurrency) is run sequentially. Results are written
 * to SQLite and a comparison table is printed at the end.
 */
public final class SuiteCommand implements Command {

  @Override
  public String name() {
    return "suite";
  }

  @Override
  public void run(Context ctx) throws Exception {
    var container = ctx.container();
    var repository = container.get(BenchRepository.class);
    var eventBus = container.get(EventBus.class);

    // Parse configuration
    var engines = parseList(ctx.get("engines", "freeway"));
    var scenarios = parseList(ctx.get("scenarios", "ping"));
    var concurrencies = parseIntList(ctx.get("concurrency", "16"));
    int requests = ctx.getInt("requests", 2000);
    int warmup = ctx.getInt("warmup", 2_000);
    int runs = ctx.getInt("runs", 5);
    int pauseMillis = ctx.getInt("pause-millis", 0);
    // Standing protocol: the tail is the signal (early rounds measure warmup).
    int medianLast = ctx.getInt("median-last", 0);
    String tasksetServer = ctx.get("taskset-server", "");
    String tasksetClient = ctx.get("taskset-client", "");
    // Split shape through the established fork path (resident server plus client in
    // separate JVMs). Absent, the server starts in this JVM — the smoke shape.
    boolean fork = ctx.has("fork");
    var mode = ctx.parse("mode", BenchMode::of, BenchMode.DEFAULT);
    var modeLabel = mode.label();
    if (mode.webSocket()) {
      if (scenarios.stream().noneMatch(s -> s.equalsIgnoreCase("ws_echo"))) {
        throw new UsageException("--mode=ws requires --scenario=ws_echo");
      }
      for (var engine : engines) {
        if (!isWsCapable(engine)) {
          throw new UsageException(
              "--mode=ws is not supported for engine '"
                  + engine
                  + "'; supported: "
                  + ServerHarness.WS_CAPABLE_ENGINES.stream()
                      .map(ServerHarness.Engine::label)
                      .sorted()
                      .collect(java.util.stream.Collectors.joining(", ")));
        }
      }
    }
    String outputPath = ctx.get("output", null);

    int total = engines.size() * scenarios.size() * concurrencies.length * runs;
    int done = 0;

    System.out.println("## Suite: " + ctx.args().toString());
    System.out.println();
    System.out.printf(
        "Engines: %s | Scenarios: %s | Concurrency: %s | "
            + "Requests: %d | Warmup: %d | Runs: %d | Mode: %s%n",
        engines, scenarios, Arrays.toString(concurrencies), requests, warmup, runs, modeLabel);
    System.out.printf("Total iterations: %d%n", total);
    System.out.println();

    // Collect all results for the final report
    var allResults = new ArrayList<SuiteResult>();

    for (var engine : engines) {
      var eng = requireEngine(engine);
      for (var scenario : scenarios) {
        var scn = requireScenario(scenario);
        if (ScenarioSpec.of(scn).echoBody()) {
          throw new UsageException(
              "Scenario ECHO_BODY is not measurable: the client cannot send a request body");
        }
        for (int concurrency : concurrencies) {
          System.out.printf("### %s / %s concurrency=%d%n", engine, scenario, concurrency);
          System.out.println();

          // One cell per (engine, scenario, concurrency): the shared bookkeeping both shapes use —
          // run row, per-round rows, median pick and dispersion.
          var cell =
              BenchCell.open(
                  repository,
                  eventBus,
                  engine,
                  scenario,
                  concurrency,
                  requests,
                  warmup,
                  runs,
                  modeLabel);

          if (fork) {
            var forked =
                ForkedRunner.run(
                    new ForkedRunner.Options(
                        engine,
                        scenario,
                        modeLabel,
                        requests,
                        concurrency,
                        warmup,
                        runs,
                        pauseMillis,
                        medianLast,
                        tasksetServer,
                        tasksetClient));
            if (forked.size() != runs) {
              throw new IllegalStateException(
                  "Forked client returned " + forked.size() + " rounds, expected " + runs);
            }
            for (int r = 0; r < runs; r++) {
              done++;
              cell.collect(r, BenchRunner.IterationResult.from(forked.get(r)));
            }
            if (cell.rounds().stream().anyMatch(BenchRunner.IterationResult::saturated)) {
              ctx.exitCode(2);
              System.out.println(
                  "GATE: load generator saturated (client CPU >= 75%) — rounds invalid");
            }
          } else
            try (var harness = ServerHarness.start(eng, scn)) {
              int port = harness.port();
              for (int r = 0; r < runs; r++) {
                done++;
                var ir =
                    BenchRunner.run(port, concurrency, requests, warmup, scn, mode.clientMode());
                cell.collect(r, ir);

                System.out.printf(
                    "  run %d/%d: rps=%s p50=%s [%d/%d]%n",
                    r + 1,
                    runs,
                    BenchFormat.rps(ir.rps()),
                    BenchFormat.micros(ir.p50us()),
                    done,
                    total);
              }
            }

          // The dispersion lands on the median round's row (inside finish), so every comparison
          // reads one row rather than pairing two.
          int medianIndex = cell.finish(medianLast);

          // Pick median iteration as representative
          allResults.add(
              new SuiteResult(engine, scenario, concurrency, cell.rounds().get(medianIndex)));
          cell.requireMeasured();
          if (cell.climbing()) {
            System.out.println(
                "WARN: "
                    + engine
                    + "/"
                    + scenario
                    + " rounds still climbing — "
                    + "warmup insufficient, median under-reports");
          }
          System.out.println();
        }
      }
    }

    // Print comprehensive comparison table
    printSummary(allResults);

    // Write report to file if requested
    if (outputPath != null && !outputPath.isBlank()) {
      BenchFormat.requireOutputExtension(outputPath, ".md", "Markdown");
      writeReport(outputPath, allResults);
    }

    System.out.printf(
        "%nSuite complete: %d iteration(s), %d total combinations.%n",
        done, engines.size() * scenarios.size() * concurrencies.length);
  }

  /** One row of the report: what was measured, plus the one measurement type. */
  private record SuiteResult(
      String engine, String scenario, int concurrency, BenchRunner.IterationResult measurement) {}

  private static void printSummary(List<SuiteResult> allResults) {
    System.out.println("## Suite Summary");
    System.out.println();
    System.out.println(summaryTable(allResults));
  }

  /** The suite table — the same renderer the Markdown report and every other command use. */
  private static String summaryTable(List<SuiteResult> allResults) {
    var rows = new ArrayList<BenchFormat.Row>();
    for (var r : allResults) {
      var m = r.measurement();
      rows.add(
          BenchFormat.Row.of(
              r.engine(),
              r.scenario(),
              String.valueOf(r.concurrency()),
              BenchFormat.rps(m.rps()),
              BenchFormat.micros(m.p50us()),
              BenchFormat.micros(m.p95us()),
              BenchFormat.micros(m.p99us()),
              String.valueOf(m.errors())));
    }
    return BenchFormat.table(
        List.of("Engine", "Scenario", "Concur", "RPS", "p50", "p95", "p99", "Errors"),
        List.of(
            BenchFormat.Align.LEFT,
            BenchFormat.Align.LEFT,
            BenchFormat.Align.RIGHT,
            BenchFormat.Align.RIGHT,
            BenchFormat.Align.RIGHT,
            BenchFormat.Align.RIGHT,
            BenchFormat.Align.RIGHT,
            BenchFormat.Align.RIGHT),
        rows);
  }

  private static void writeReport(String outputPath, List<SuiteResult> allResults)
      throws Exception {
    var report = new StringBuilder();
    report.append("# Suite Report\n\n");
    report.append(summaryTable(allResults));
    Files.writeString(Path.of(outputPath), report.toString(), StandardCharsets.UTF_8);
    System.out.println("Report written to " + outputPath);
  }

  private static boolean isWsCapable(String engine) {
    return ServerHarness.WS_CAPABLE_ENGINES.contains(requireEngine(engine));
  }

  /** Resolves an engine name, reporting an unknown one as a usage error. */
  private static ServerHarness.Engine requireEngine(String engine) {
    try {
      return ServerHarness.Engine.fromString(engine);
    } catch (IllegalArgumentException e) {
      throw new UsageException("--engines: unknown engine '" + engine + "'");
    }
  }

  /** Resolves a scenario name, reporting an unknown one as a usage error. */
  private static ServerHarness.Scenario requireScenario(String scenario) {
    try {
      return ServerHarness.Scenario.valueOf(scenario.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new UsageException("--scenarios: unknown scenario '" + scenario + "'");
    }
  }

  /**
   * One cell's bookkeeping lives in {@link BenchCell} — the two shapes (forked client, in-JVM
   * smoke) run the same rounds and must persist and announce them the same way, and that is the one
   * place that does it.
   */
  private static List<String> parseList(String value) {
    var list = new ArrayList<String>();
    for (var s : value.split(",")) {
      var trimmed = s.trim();
      if (!trimmed.isEmpty()) list.add(trimmed);
    }
    return list;
  }

  private static int[] parseIntList(String value) {
    var parts = value.split(",");
    var result = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        result[i] = Integer.parseInt(parts[i].trim());
      } catch (NumberFormatException e) {
        throw new UsageException(
            "--concurrency must be a comma-separated list of integers, got '" + value + "'");
      }
    }
    return result;
  }
}
