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

package com.jujin.freeway.bench.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.cli.CliModule;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.ioc.ModuleNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bench-table statements: filters, ordering and the baseline lookup, exercised without going
 * through a CLI command.
 */
class BenchRepositoryTest {

  private AppRuntime app;
  private BenchRepository repository;

  @BeforeEach
  void setUp() {
    System.setProperty("freeway.db.url", "jdbc:sqlite::memory:");
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.max-size", "1");
    System.setProperty("freeway.db.pool.min-idle", "0");
    // The schema is created by the migration runtime hook, so the app has to start
    // (Freeway.create alone never runs hooks).
    app =
        FreewayApp.create(
                ModuleNode.app(
                    "bench-repo-test", BenchDbModule.class, DbModule.class, CliModule.class))
            .autoDiscovery(false)
            .shutdownHook(false)
            .start();
    repository = new BenchRepository(app.get(Database.class), new CoercerDefault());
  }

  @AfterEach
  void tearDown() {
    app.close();
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
  void recentRunsHonourTheEngineFilterAndNewestFirstOrder() {
    long ping = insertRun("freeway", "ping");
    long json = insertRun("undertow-native", "json");

    assertEquals(
        List.of(json, ping),
        repository.recentRuns(null, 10).stream().map(BenchmarkRun::id).toList(),
        "newest first");
    assertEquals(
        List.of(json),
        repository.recentRuns("undertow-native", 10).stream().map(BenchmarkRun::id).toList(),
        "engine filter");
    assertEquals(1, repository.recentRuns(null, 1).size(), "limit");
  }

  @Test
  void resultsForKeepsInsertOrderAndRunsSinceFiltersByBenchmark() {
    long runId = insertRun("freeway", "ping");
    long first = repository.insertResult(result(runId, "freeway/ping", 100));
    long second = repository.insertResult(result(runId, "freeway/ping", 200));
    long other = repository.insertResult(result(runId, "freeway/json", 300));
    repository.recordDispersion(second, 12.5);

    assertEquals(
        List.of(first, second, other),
        repository.resultsFor(runId).stream().map(BenchmarkResult::id).toList(),
        "insert order is the report order");
    assertEquals(
        12.5, repository.resultsFor(runId).get(1).scoreError(), "the dispersion lands on that row");
    assertEquals(
        List.of(other),
        repository.resultsSince(30, null, "freeway/json").stream()
            .map(BenchmarkResult::id)
            .toList(),
        "benchmark filter");
    assertEquals(3, repository.resultsSince(30, "freeway", null).size());
    assertTrue(repository.resultsSince(30, "nope", null).isEmpty(), "engine filter");
  }

  @Test
  void previousRunIdForPicksTheBestEarlierMatchingRun() {
    long weak = insertRun("freeway", "ping");
    repository.insertResult(result(weak, "freeway/ping", 100));
    long strong = insertRun("freeway", "ping");
    repository.insertResult(result(strong, "freeway/ping", 900));
    long differentScenario = insertRun("freeway", "json");
    repository.insertResult(result(differentScenario, "freeway/json", 5000));
    long candidate = insertRun("freeway", "ping");

    var baseline = repository.findRun(candidate).flatMap(repository::previousRunIdFor);
    assertEquals(strong, baseline.orElseThrow(), "highest score wins, not the most recent");
    assertTrue(
        repository.findRun(weak).flatMap(repository::previousRunIdFor).isEmpty(),
        "no earlier match means no baseline");
  }

  private long insertRun(String engine, String scenario) {
    return repository.insertRun(BenchmarkRun.create(engine, scenario, 2, 100, 10, 1));
  }

  private static BenchmarkResult result(long runId, String benchmark, double score) {
    return BenchmarkResult.forHttpIteration(runId, benchmark, "keepalive", score, 100, 200, 300, 0);
  }
}
