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
import com.jujin.freeway.bench.event.BenchEvent;
import com.jujin.freeway.bench.harness.ServerHarness;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.bench.run.BenchMode;
import com.jujin.freeway.bench.run.BenchRunner;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.ioc.EventBus;
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
    var db = container.get(Database.class);
    var coercer = container.get(Coercer.class);
    var repository = new BenchRepository(db, coercer);
    var eventBus = container.get(EventBus.class);

    // Parse configuration
    var engines = parseList(ctx.get("engines", "freeway"));
    var scenarios = parseList(ctx.get("scenarios", "ping"));
    var concurrencies = parseIntList(ctx.get("concurrency", "16"));
    int requests = ctx.getInt("requests", 2000);
    int warmup = ctx.getInt("warmup", 200);
    int runs = ctx.getInt("runs", 3);
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
                  + "'; supported: freeway, undertow-native, jetty-native");
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
        for (int concurrency : concurrencies) {
          // Create run record
          var run = BenchmarkRun.create(engine, scenario, concurrency, requests, warmup, runs);
          long runId = repository.insertRun(run);
          eventBus.publish(new BenchEvent.RunStarted(run));

          System.out.printf("### %s / %s concurrency=%d%n", engine, scenario, concurrency);
          System.out.println();

          var scores = new double[runs];
          var resultIds = new long[runs];
          List<BenchRunner.IterationResult> iterationResults = new ArrayList<>();

          try (var harness = ServerHarness.start(eng, scn)) {
            int port = harness.port();
            for (int r = 0; r < runs; r++) {
              done++;
              var ir = BenchRunner.run(port, concurrency, requests, warmup, scn, mode.clientMode());
              scores[r] = ir.rps();
              iterationResults.add(ir);

              var result =
                  BenchmarkResult.forHttpIteration(
                      runId,
                      engine + "/" + scenario,
                      modeLabel,
                      ir.rps(),
                      ir.p50us(),
                      ir.p95us(),
                      ir.p99us(),
                      ir.errors());
              resultIds[r] = repository.insertResult(result);
              eventBus.publish(new BenchEvent.ResultCollected(result));

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

          // The dispersion belongs on the row every comparison prints: the
          // median iteration. Its id came from the insert, so there is nothing
          // to re-query.
          int medianIndex = BenchRunner.medianIndex(iterationResults);
          double error = runs > 1 ? BenchRunner.stddev(scores) : 0;
          if (runs > 1) {
            repository.recordDispersion(resultIds[medianIndex], error);
          }

          // Pick median iteration as representative
          allResults.add(
              new SuiteResult(engine, scenario, concurrency, iterationResults.get(medianIndex)));
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
    var e = requireEngine(engine);
    return e == ServerHarness.Engine.FREEWAY
        || e == ServerHarness.Engine.UNDERTOW_NATIVE
        || e == ServerHarness.Engine.JETTY_NATIVE;
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
