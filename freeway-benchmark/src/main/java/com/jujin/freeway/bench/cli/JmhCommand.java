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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runs JMH microbenchmarks and persists their scores in the same tables as the HTTP runs, so the
 * protocol's "decision-grade input" finally reaches {@code list}, {@code history} and {@code
 * compare} instead of living only in a console table.
 *
 * <pre>
 * bench jmh --include=com.jujin.freeway.bench.jmh.RouteIndexBenchmark
 * bench jmh --include=.*Codec.* --forks=0 --warmup=1 --iterations=1 --time=1s
 * </pre>
 *
 * <p>The rows carry {@code Score ± Score Error} and JMH's own unit. The run row records the JMH
 * parameter block in its existing columns — engine {@code jmh}, scenario the include pattern,
 * concurrency = forks, requests = measurement iterations, warmup = warmup iterations, runs =
 * benchmark methods measured — so a later comparison can tell a protocol-conforming run from a
 * local-iteration one. JMH reports a NaN score error when it cannot estimate one (a single
 * measurement iteration); that is persisted as 0, since "no estimate" is not a failed row.
 */
public final class JmhCommand implements Command {

  @Override
  public String name() {
    return "jmh";
  }

  @Override
  public void run(Context ctx) throws Exception {
    String include = ctx.get("include", ".*Benchmark");
    int forks = ctx.getInt("forks", 2);
    int warmupIterations = ctx.getInt("warmup", 5);
    int measureIterations = ctx.getInt("iterations", 5);
    TimeValue time = TimeValue.fromString(ctx.get("time", "1s"));
    String mode = ctx.get("bench-mode", "thrpt").toLowerCase(Locale.ROOT);

    var container = ctx.container();
    var repository =
        new BenchRepository(container.get(Database.class), container.get(Coercer.class));

    System.out.printf(
        "bench jmh --include=%s --forks=%d --warmup=%d --iterations=%d --time=%s --bench-mode=%s%n",
        include, forks, warmupIterations, measureIterations, time, mode);

    var options =
        new OptionsBuilder()
            .include(include)
            .forks(forks)
            .warmupIterations(warmupIterations)
            .warmupTime(time)
            .measurementIterations(measureIterations)
            .measurementTime(time)
            .mode(org.openjdk.jmh.annotations.Mode.Throughput)
            // No resultFormat: the scores go to the database (and the console) — JMH's own file
            // output would drop a stray jmh-result.text into the working directory.
            .shouldDoGC(false)
            .build();

    List<RunResult> jmhResults;
    try {
      jmhResults = new ArrayList<>(new Runner(options).run());
    } catch (org.openjdk.jmh.runner.NoBenchmarksException e) {
      throw new UsageException("No JMH benchmark matched --include=" + include);
    } catch (Exception e) {
      throw new IllegalStateException("JMH run failed: " + e.getMessage(), e);
    }
    if (jmhResults.isEmpty()) {
      throw new UsageException("No JMH benchmark matched --include=" + include);
    }

    // One run row per invocation: the JMH parameters are what a comparison needs to know.
    long runId =
        repository.insertRun(
            BenchmarkRun.create(
                "jmh", include, forks, measureIterations, warmupIterations, jmhResults.size()));

    for (RunResult result : jmhResults) {
      var primary = result.getPrimaryResult();
      // BenchmarkParams already carries the fully qualified "class.method".
      String benchmark = result.getParams().getBenchmark();
      String jmhMode = result.getParams().getMode().shortLabel();
      double score = primary.getScore();
      if (!Double.isFinite(score)) {
        throw new IllegalStateException("JMH produced no finite score for " + benchmark);
      }
      // JMH reports NaN when it cannot estimate the error (a single measurement
      // iteration, an -i 1 local run); the column is NOT NULL, and "no estimate"
      // is honestly 0 rather than a rejected row.
      double scoreError = primary.getScoreError();
      if (!Double.isFinite(scoreError)) scoreError = 0;
      long id =
          repository.insertResult(
              BenchmarkResult.forJmh(
                  runId, benchmark, jmhMode, score, scoreError, primary.getScoreUnit()));
      System.out.printf(
          "  %s  %s ± %s %s%n", benchmark, fmt(score), fmt(scoreError), primary.getScoreUnit());
      if (id <= 0) {
        throw new IllegalStateException("Failed to persist JMH result: " + benchmark);
      }
    }
    long seconds = time.getTimeUnit().toSeconds(time.getTime()) * measureIterations * forks;
    System.out.printf(
        "Done. Run #%d saved (%d benchmark(s), mode=%s, ~%ds of measurement).%n",
        runId, jmhResults.size(), mode, seconds);
  }

  private static String fmt(double value) {
    return BenchFormat.rps(value);
  }
}
