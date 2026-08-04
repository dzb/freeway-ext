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
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code bench history} — shows performance trend for a benchmark.
 *
 * <p>Arguments:
 *
 * <pre>
 * --bench=<name>   benchmark name filter (e.g. "freeway/ping")
 * --days=<n>       only show runs from the last N days (default: 30)
 * --engine=<name>  filter by engine (optional)
 * </pre>
 */
public final class HistoryCommand implements Command {

  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  @Override
  public void run(Context ctx) throws Exception {
    var container = ctx.container();
    var db = container.get(Database.class);
    var coercer = container.get(Coercer.class);
    var orm = new Orm(db, coercer);

    String benchFilter = ctx.get("bench", null);
    String engineFilter = ctx.get("engine", null);
    int days = ctx.getInt("days", 30);
    if (days <= 0) {
      throw new IllegalArgumentException("--days must be a positive integer");
    }

    // Fetch runs within the time window
    String runSql =
        "SELECT * FROM bench_runs WHERE created_at >= datetime('now', ?)"
            + (engineFilter != null ? " AND engine = ?" : "")
            + " ORDER BY created_at ASC";
    var runParams = new ArrayList<Object>();
    runParams.add("-" + days + " days");
    if (engineFilter != null) runParams.add(engineFilter);
    List<BenchmarkRun> runs = db.query(runSql, runParams.toArray()).list(BenchmarkRun.class);

    if (runs.isEmpty()) {
      System.out.println("No runs found in the last " + days + " days.");
      return;
    }

    // Fetch results via a JOIN with the same window — avoids SQLite's
    // per-statement parameter limit when the run count is large.
    String sql =
        "SELECT res.* FROM bench_results res JOIN bench_runs r"
            + " ON res.run_id = r.id"
            + " WHERE r.created_at >= datetime('now', ?)"
            + (engineFilter != null ? " AND r.engine = ?" : "")
            + (benchFilter != null ? " AND res.benchmark = ?" : "")
            + " ORDER BY res.run_id ASC";
    var params = new ArrayList<Object>();
    params.add("-" + days + " days");
    if (engineFilter != null) params.add(engineFilter);
    if (benchFilter != null) params.add(benchFilter);
    List<BenchmarkResult> results = db.query(sql, params.toArray()).list(BenchmarkResult.class);

    if (results.isEmpty()) {
      System.out.println("No results found for the given filters.");
      return;
    }

    // Group by benchmark name
    var byBenchmark = results.stream().collect(Collectors.groupingBy(BenchmarkResult::benchmark));

    for (var entry : byBenchmark.entrySet()) {
      String benchName = entry.getKey();
      var benchResults = entry.getValue();

      System.out.println();
      System.out.println("## " + benchName);
      System.out.println();
      System.out.printf(
          "| %-4s | %-20s | %9s | %6s | %6s | %6s | %s |%n",
          "Run", "Created", "RPS", "p50", "p95", "p99", "Δ vs best");
      System.out.println(
          "|"
              + "─".repeat(6)
              + "|"
              + "─".repeat(22)
              + "|"
              + "─".repeat(11)
              + "|"
              + "─".repeat(8)
              + "|"
              + "─".repeat(8)
              + "|"
              + "─".repeat(8)
              + "|"
              + "─".repeat(12)
              + "|");

      // Find best score for this benchmark
      double bestScore = benchResults.stream().mapToDouble(BenchmarkResult::score).max().orElse(1);

      // Find corresponding run for each result
      var runIndex = runs.stream().collect(Collectors.toMap(BenchmarkRun::id, r -> r));

      for (var r : benchResults) {
        var run = runIndex.get(r.runId());
        String created =
            run != null && run.createdAt() != null
                ? FMT.format(run.createdAt().atZone(ZoneId.systemDefault()))
                : "—";
        double delta = (r.score() - bestScore) / bestScore * 100;

        System.out.printf(
            "| %-4d | %-20s | %9s | %6s | %6s | %6s | %+10.1f%% |%n",
            r.runId(),
            created,
            BenchFormat.rps(r.score()),
            r.p50us() + "μs",
            r.p95us() + "μs",
            r.p99us() + "μs",
            delta);
      }
    }

    System.out.println();
    System.out.printf("Showing %d run(s) from the last %d day(s).%n", runs.size(), days);
  }
}
