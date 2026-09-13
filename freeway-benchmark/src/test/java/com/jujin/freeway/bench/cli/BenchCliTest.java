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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.db.BenchDbModule;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.ioc.ModuleNode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The pieces of the benchmark CLI that must behave identically in both commands. */
class BenchCliTest {

  @AfterEach
  void clearProperties() {
    System.clearProperty("freeway.db.url");
    System.clearProperty("freeway.db.username");
    System.clearProperty("freeway.db.password");
    System.clearProperty("freeway.db.pool.max-size");
    System.clearProperty("freeway.db.pool.min-idle");
  }

  @Test
  void medianIndexPicksTheMedianByRpsNotByPosition() {
    var iterations =
        List.of(
            new BenchRunner.IterationResult(100, 1, 2, 3, 0),
            new BenchRunner.IterationResult(900, 1, 2, 3, 0),
            new BenchRunner.IterationResult(500, 1, 2, 3, 0));

    assertEquals(2, BenchRunner.medianIndex(iterations), "the 500 rps iteration is the median");
    assertEquals(
        1,
        BenchRunner.medianIndex(List.of(iterations.get(1), iterations.get(2), iterations.get(0))),
        "the answer follows rps, not the order the iterations arrived in");
  }

  @Test
  void forHttpIterationStampsTheMeaningOfARow() {
    BenchmarkResult row =
        BenchmarkResult.forHttpIteration(7L, "freeway/ping", "keepalive", 1234.5, 10, 20, 30, 1);

    assertEquals(0L, row.id(), "the generated key is assigned by the database");
    assertEquals("req/s", row.unit());
    assertEquals(0.0, row.scoreError(), "run-level dispersion is recorded on the median row later");
    assertEquals(1, row.errors());
  }

  @Test
  void runCommandPersistsEveryIterationAndMarksOnlyTheMedianRow() throws Exception {
    // End-to-end through the real command: three iterations persisted, the
    // run-level dispersion written to the median row (the one comparisons read),
    // and nothing left to re-query.
    try (AppRuntime app = app()) {
      Database db = app.get(Database.class);
      int exitCode =
          CliModule.dispatch(
              CliModule.container(),
              new String[] {
                "run",
                "--engine=freeway",
                "--scenario=ping",
                "--requests=200",
                "--warmup=50",
                "--runs=3"
              });
      assertEquals(0, exitCode, "a successful run asks for no failure exit code");

      List<BenchmarkResult> rows =
          db.query("SELECT * FROM bench_results ORDER BY id ASC", new Object[0])
              .list(BenchmarkResult.class);
      assertEquals(3, rows.size(), "one row per iteration");
      assertTrue(rows.stream().allMatch(r -> "req/s".equals(r.unit())));

      Set<Long> marked =
          rows.stream()
              .filter(r -> r.scoreError() > 0)
              .map(BenchmarkResult::id)
              .collect(Collectors.toSet());
      assertEquals(1, marked.size(), "the dispersion lands on exactly one row");

      List<Double> scores = rows.stream().map(BenchmarkResult::score).sorted().toList();
      double medianScore = scores.get(1);
      BenchmarkResult medianRow =
          rows.stream().filter(r -> marked.contains(r.id())).findFirst().orElseThrow();
      assertEquals(
          medianScore,
          medianRow.score(),
          "the marked row is the median iteration, not just any row");
      assertNotEquals(0L, medianRow.id(), "the id used by the update came from the insert");
    }
  }

  private static AppRuntime app() {
    System.setProperty("freeway.db.url", "jdbc:sqlite::memory:");
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.max-size", "1");
    // One connection for the in-memory database, and no idle floor above it (as BenchApp does).
    System.setProperty("freeway.db.pool.min-idle", "0");
    return FreewayApp.of(
            ModuleNode.app(
                "freeway-benchmark", BenchDbModule.class, DbModule.class, CliModule.class))
        .autoDiscovery(false)
        .shutdownHook(false)
        .start();
  }
}
