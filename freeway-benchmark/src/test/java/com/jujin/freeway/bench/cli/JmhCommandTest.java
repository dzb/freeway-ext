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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.db.BenchDbModule;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.ioc.ModuleNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code bench jmh} persists a real (tiny) JMH run into the same tables the HTTP runs use, so the
 * microbenchmarks reach {@code list}/{@code history}/{@code compare}.
 */
class JmhCommandTest {

  @AfterEach
  void clearProperties() {
    for (String key :
        List.of(
            "freeway.db.url",
            "freeway.db.username",
            "freeway.db.password",
            "freeway.db.pool.max-size",
            "freeway.db.pool.min-idle")) {
      System.clearProperty(key);
    }
  }

  @Test
  void persistsOneRowPerJmhBenchmarkMethod() throws Exception {
    try (AppRuntime app = app()) {
      int exitCode =
          CliModule.dispatch(
              new String[] {
                "jmh",
                "--include=com.jujin.freeway.bench.jmh.RouteIndexBenchmark",
                "--forks=0",
                "--warmup=1",
                "--iterations=1",
                "--time=100ms"
              });
      assertEquals(0, exitCode, "a successful JMH run asks for no failure exit code");

      Database db = app.get(Database.class);
      List<BenchmarkRun> runs =
          db.query("SELECT * FROM bench_runs", new Object[0]).list(BenchmarkRun.class);
      assertEquals(1, runs.size(), "one run row per invocation");
      assertEquals("jmh", runs.getFirst().engine());
      // The run row records the JMH parameter block: concurrency = forks, requests = measurement
      // iterations, warmup = warmup iterations, runs = benchmark methods measured.
      assertEquals(0, runs.getFirst().concurrency(), "forks");
      assertEquals(1, runs.getFirst().requests(), "measurement iterations");
      assertEquals(1, runs.getFirst().warmup(), "warmup iterations");
      assertEquals(3, runs.getFirst().runs(), "benchmark methods measured");

      List<BenchmarkResult> rows =
          db.query("SELECT * FROM bench_results ORDER BY id ASC", new Object[0])
              .list(BenchmarkResult.class);
      assertEquals(3, rows.size(), "one row per benchmark method");
      assertTrue(
          rows.stream().allMatch(r -> r.benchmark().startsWith("com.jujin.freeway.bench.jmh.")),
          "the benchmark name is the fully qualified class.method: "
              + rows.stream().map(BenchmarkResult::benchmark).toList());
      assertTrue(rows.stream().allMatch(r -> "ops/s".equals(r.unit())), "JMH's own unit");
      assertTrue(rows.stream().allMatch(r -> "thrpt".equals(r.mode())), "JMH's own mode");
      assertTrue(rows.stream().allMatch(r -> r.score() > 0), "a real score");
      assertTrue(rows.stream().allMatch(r -> r.scoreError() >= 0), "Score Error is persisted");
    }
  }

  @Test
  void includeWithoutAMatchIsAUsageError() throws Exception {
    try (AppRuntime app = app()) {
      String[] args = {
        "jmh",
        "--include=com.jujin.freeway.bench.NoSuchBenchmark",
        "--forks=0",
        "--warmup=1",
        "--iterations=1",
        "--time=100ms"
      };
      UsageException failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              UsageException.class, () -> CliModule.dispatch(args));
      assertTrue(failure.getMessage().contains("No JMH benchmark"), failure.getMessage());
    }
  }

  private static AppRuntime app() {
    System.setProperty("freeway.db.url", "jdbc:sqlite::memory:");
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.max-size", "1");
    System.setProperty("freeway.db.pool.min-idle", "0");
    return FreewayApp.of(
            ModuleNode.app(
                "freeway-benchmark", BenchDbModule.class, DbModule.class, CliModule.class))
        .autoDiscovery(false)
        .shutdownHook(false)
        .start();
  }
}
