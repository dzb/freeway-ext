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
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
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
  public String name() {
    return "history";
  }

  @Override
  public void run(Context ctx) throws Exception {
    var container = ctx.container();
    var db = container.get(Database.class);

    String benchFilter = ctx.get("bench", null);
    String engineFilter = ctx.get("engine", null);
    int days = ctx.getInt("days", 30);
    if (days <= 0) {
      throw new IllegalArgumentException("--days must be a positive integer");
    }

    var repository = new BenchRepository(db, container.get(Coercer.class));
    List<BenchmarkRun> runs = repository.runsSince(days, engineFilter);

    if (runs.isEmpty()) {
      System.out.println("No runs found in the last " + days + " days.");
      return;
    }

    // The join with the same window avoids SQLite's per-statement parameter
    // limit when the run count is large.
    List<BenchmarkResult> results = repository.resultsSince(days, engineFilter, benchFilter);

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

      // Find best score for this benchmark
      double bestScore = benchResults.stream().mapToDouble(BenchmarkResult::score).max().orElse(1);

      // Find corresponding run for each result
      var runIndex = runs.stream().collect(Collectors.toMap(BenchmarkRun::id, r -> r));

      var rows = new ArrayList<BenchFormat.Row>();
      for (var r : benchResults) {
        var run = runIndex.get(r.runId());
        String created =
            run != null && run.createdAt() != null
                ? FMT.format(run.createdAt().atZone(ZoneId.systemDefault()))
                : "—";
        double delta = bestScore > 0 ? (r.score() - bestScore) / bestScore : 0;

        rows.add(
            BenchFormat.Row.of(
                String.valueOf(r.runId()),
                created,
                BenchFormat.rps(r.score()),
                BenchFormat.micros(r.p50us()),
                BenchFormat.micros(r.p95us()),
                BenchFormat.micros(r.p99us()),
                BenchFormat.delta(delta)));
      }
      System.out.println(
          BenchFormat.table(
              List.of("Run", "Created", "RPS", "p50", "p95", "p99", "Δ vs best"),
              List.of(
                  BenchFormat.Align.RIGHT,
                  BenchFormat.Align.LEFT,
                  BenchFormat.Align.RIGHT,
                  BenchFormat.Align.RIGHT,
                  BenchFormat.Align.RIGHT,
                  BenchFormat.Align.RIGHT,
                  BenchFormat.Align.RIGHT),
              rows));
    }

    System.out.println();
    System.out.printf("Showing %d run(s) from the last %d day(s).%n", runs.size(), days);
  }
}
