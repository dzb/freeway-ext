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

import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * {@code bench compare} — compares two benchmark runs with regression detection.
 *
 * <p>Thresholds (per BENCHMARK_PROTOCOL.md):
 *
 * <ul>
 *   <li>RPS change &lt; 3% — noise, no flag
 *   <li>RPS drop &ge; 3% — ⚠ regression
 *   <li>p95/p99 up &ge; 5% — ⚠ latency regression
 * </ul>
 *
 * <p>Arguments:
 *
 * <pre>
 * --from=&lt;run-id&gt;      baseline run ID (default: best prior matching run)
 * --to=&lt;run-id&gt;        candidate run ID (default: latest)
 * </pre>
 */
public final class CompareCommand implements Command {

  private static final double NOISE_THRESHOLD_RPS = 0.03; // 3% RPS noise
  private static final double REGRESSION_LATENCY = 0.05; // 5% latency regression
  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Override
  public void run(Context ctx) throws Exception {
    var container = ctx.container();
    var db = container.get(Database.class);
    var coercer = container.get(Coercer.class);
    var orm = new Orm(db, coercer);

    // Determine run IDs
    var allRuns = orm.findAll(BenchmarkRun.class, "id ASC", 0, 0);
    if (allRuns.isEmpty()) {
      System.out.println("No runs found.");
      return;
    }

    int toId = ctx.getInt("to", (int) allRuns.getLast().id());
    var toRun =
        orm.findById(BenchmarkRun.class, (long) toId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + toId));

    // Auto-detect baseline: if --from not specified, find best previous run
    // with the same engine+scenario+concurrency as the candidate
    int fromId;
    if (ctx.args().containsKey("from")) {
      fromId = ctx.getInt("from", 0);
    } else {
      fromId =
          db.query(
                  "SELECT r.id FROM bench_runs r JOIN bench_results res ON r.id = res.run_id "
                      + "WHERE r.engine = ? AND r.scenario = ? AND r.concurrency = ? "
                      + "AND r.id < ? GROUP BY r.id ORDER BY MAX(res.score) DESC LIMIT 1",
                  toRun.engine(),
                  toRun.scenario(),
                  toRun.concurrency(),
                  toId)
              .one(Integer.class)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "No earlier run matches engine="
                              + toRun.engine()
                              + " scenario="
                              + toRun.scenario()
                              + " concurrency="
                              + toRun.concurrency()
                              + " before run #"
                              + toId
                              + "; specify --from explicitly"));
    }

    var fromRun =
        orm.findById(BenchmarkRun.class, (long) fromId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + fromId));

    // Fetch results
    var fromResults =
        db.query("SELECT * FROM bench_results WHERE run_id = ?", fromId)
            .list(BenchmarkResult.class);
    var toResults =
        db.query("SELECT * FROM bench_results WHERE run_id = ?", toId).list(BenchmarkResult.class);

    // Index by benchmark name — pick last result if duplicates (multiple run iterations)
    var fromIndex =
        fromResults.stream()
            .collect(Collectors.toMap(BenchmarkResult::benchmark, r -> r, (a, b) -> b));
    var toIndex =
        toResults.stream()
            .collect(Collectors.toMap(BenchmarkResult::benchmark, r -> r, (a, b) -> b));

    // Print header
    System.out.println();
    System.out.println(
        "## Compare: Run #" + fromId + " (baseline) vs Run #" + toId + " (candidate)");
    System.out.println();
    System.out.printf(
        "| %-28s | %12s %12s %7s | %12s %12s %7s | %8s |%n",
        "Benchmark", "RPS", "p50", "p95", "RPS", "p50", "p95", "ΔRPS");
    System.out.println(
        "|"
            + "─".repeat(30)
            + "|"
            + "─".repeat(34)
            + "|"
            + "─".repeat(34)
            + "|"
            + "─".repeat(10)
            + "|");

    // Collect all unique benchmark names
    var allBenchmarks = new LinkedHashSet<String>();
    allBenchmarks.addAll(fromIndex.keySet());
    allBenchmarks.addAll(toIndex.keySet());

    var regressions = new ArrayList<String>();
    var improvements = new ArrayList<String>();

    for (String bench : allBenchmarks) {
      var f = fromIndex.get(bench);
      var t = toIndex.get(bench);

      String fromRps = f != null ? BenchFormat.rps(f.score()) : "—";
      String toRps = t != null ? BenchFormat.rps(t.score()) : "—";
      String fromP50 = f != null ? f.p50us() + "μs" : "—";
      String toP50 = t != null ? t.p50us() + "μs" : "—";
      String fromP95 = f != null ? f.p95us() + "μs" : "—";
      String toP95 = t != null ? t.p95us() + "μs" : "—";

      String flag = "";
      if (f != null && t != null) {
        double rpsDelta = (t.score() - f.score()) / f.score();
        double p95Delta = f.p95us() > 0 ? (double) (t.p95us() - f.p95us()) / f.p95us() : 0;
        double p99Delta = f.p99us() > 0 ? (double) (t.p99us() - f.p99us()) / f.p99us() : 0;

        // Regression detection
        if (rpsDelta < -NOISE_THRESHOLD_RPS) {
          flag = " ⚠RPS↓";
          regressions.add(bench + " RPS " + BenchFormat.delta(rpsDelta));
        }
        if (p95Delta > REGRESSION_LATENCY || p99Delta > REGRESSION_LATENCY) {
          if (!flag.contains("⚠")) flag = " ⚠LAT";
          else flag = " ⚠RPS+LAT";
          regressions.add(
              bench
                  + " latency p95 "
                  + BenchFormat.delta(p95Delta)
                  + " p99 "
                  + BenchFormat.delta(p99Delta));
        }
        if (rpsDelta > NOISE_THRESHOLD_RPS) {
          improvements.add(bench + " RPS " + BenchFormat.delta(rpsDelta));
        }

        var delta = BenchFormat.delta(rpsDelta) + flag;
        System.out.printf(
            "| %-28s | %12s %12s %7s | %12s %12s %7s | %s |%n",
            bench, fromRps, fromP50, fromP95, toRps, toP50, toP95, delta);
      } else {
        String delta = "—";
        System.out.printf(
            "| %-28s | %12s %12s %7s | %12s %12s %7s | %8s |%n",
            bench, fromRps, fromP50, fromP95, toRps, toP50, toP95, delta);
      }
    }

    System.out.println();
    System.out.println("Baseline:  " + formatRun(fromRun));
    System.out.println("Candidate: " + formatRun(toRun));

    // Print regression summary
    if (!regressions.isEmpty()) {
      System.out.println();
      System.out.println("⚠  **REGRESSIONS DETECTED:**");
      for (var r : regressions) {
        System.out.println("  - " + r);
      }
    }
    if (!improvements.isEmpty()) {
      System.out.println();
      System.out.println("✅ Improvements:");
      for (var i : improvements) {
        System.out.println("  - " + i);
      }
    }
    if (regressions.isEmpty() && improvements.isEmpty()) {
      System.out.println();
      System.out.println("✅ No significant changes (all within noise threshold).");
    }
  }

  private static String formatRun(BenchmarkRun run) {
    return String.format(
        "#%d %s/%s concurrency=%d requests=%d warmup=%d %s",
        run.id(),
        run.engine(),
        run.scenario(),
        run.concurrency(),
        run.requests(),
        run.warmup(),
        run.createdAt() != null ? FMT.format(run.createdAt().atZone(ZoneId.systemDefault())) : "—");
  }
}
