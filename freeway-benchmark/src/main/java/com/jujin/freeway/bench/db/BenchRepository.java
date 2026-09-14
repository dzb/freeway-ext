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

import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every bench-table statement in one place. The CLI commands used to each hand-write the same
 * queries in slightly different ways (a concatenated {@code WHERE} with a duplicated {@code
 * db.query} branch, two identical result fetches in {@code compare}, an inline join); keeping the
 * SQL here means one spelling per query and a place to test the filters without going through a
 * command.
 *
 * <p>Entity inserts/reads go through {@link Orm}; the queries with a dynamic {@code WHERE} stay
 * hand-written because the column list and the ordering are part of the report contract.
 */
public final class BenchRepository {

  private final Database db;
  private final Orm orm;

  public BenchRepository(Database db, Coercer coercer) {
    this.db = db;
    this.orm = new Orm(db, coercer);
  }

  /** Persists a run and returns its generated id. */
  public long insertRun(BenchmarkRun run) {
    return orm.insert(run).longKey();
  }

  /** Persists one iteration row and returns its generated id. */
  public long insertResult(BenchmarkResult result) {
    return orm.insert(result).longKey();
  }

  /**
   * Records the run-level dispersion on the row comparisons read (the median iteration). The id
   * comes from the insert, so nothing is re-queried.
   */
  public void recordDispersion(long resultId, double dispersion) {
    db.execute("UPDATE bench_results SET score_error = ? WHERE id = ?", dispersion, resultId);
  }

  /** All runs, oldest first. */
  public List<BenchmarkRun> allRuns() {
    return orm.findAll(BenchmarkRun.class, Orm.FindOptions.defaults().withOrderBy("id ASC"));
  }

  public Optional<BenchmarkRun> findRun(long id) {
    return orm.findById(BenchmarkRun.class, id);
  }

  /** The most recent runs, newest first, optionally restricted to one engine. */
  public List<BenchmarkRun> recentRuns(String engineFilter, int limit) {
    String sql =
        "SELECT id, engine, scenario, concurrency, requests, "
            + "warmup, runs, commit_sha, jdk_info, os_info, cpu_info, created_at "
            + "FROM bench_runs";
    if (engineFilter != null) {
      return db.query(
              sql + " WHERE engine = ? ORDER BY created_at DESC LIMIT ?", engineFilter, limit)
          .list(BenchmarkRun.class);
    }
    return db.query(sql + " ORDER BY created_at DESC LIMIT ?", limit).list(BenchmarkRun.class);
  }

  /**
   * Runs created within the last {@code days}, oldest first, optionally restricted to one engine.
   */
  public List<BenchmarkRun> runsSince(int days, String engineFilter) {
    String sql =
        "SELECT * FROM bench_runs WHERE created_at >= datetime('now', ?)"
            + (engineFilter != null ? " AND engine = ?" : "")
            + " ORDER BY created_at ASC";
    var params = new ArrayList<Object>();
    params.add("-" + days + " days");
    if (engineFilter != null) params.add(engineFilter);
    return db.query(sql, params.toArray()).list(BenchmarkRun.class);
  }

  /**
   * Results in the time window via a join with the runs table — avoids SQLite's per-statement
   * parameter limit when the run count is large. Filters are optional.
   */
  public List<BenchmarkResult> resultsSince(int days, String engineFilter, String benchmarkFilter) {
    String sql =
        "SELECT res.* FROM bench_results res JOIN bench_runs r"
            + " ON res.run_id = r.id"
            + " WHERE r.created_at >= datetime('now', ?)"
            + (engineFilter != null ? " AND r.engine = ?" : "")
            + (benchmarkFilter != null ? " AND res.benchmark = ?" : "")
            + " ORDER BY res.run_id ASC";
    var params = new ArrayList<Object>();
    params.add("-" + days + " days");
    if (engineFilter != null) params.add(engineFilter);
    if (benchmarkFilter != null) params.add(benchmarkFilter);
    return db.query(sql, params.toArray()).list(BenchmarkResult.class);
  }

  /**
   * The results of one run in insert order. The ordering is what makes "the last iteration wins"
   * deterministic when several iterations share a benchmark name.
   */
  public List<BenchmarkResult> resultsFor(long runId) {
    return db.query("SELECT * FROM bench_results WHERE run_id = ? ORDER BY id ASC", runId)
        .list(BenchmarkResult.class);
  }

  /**
   * The best earlier run with the same engine, scenario and concurrency as {@code candidate}, or
   * empty when there is none. "Best" is the highest score its results reached.
   */
  public Optional<Long> previousRunIdFor(BenchmarkRun candidate) {
    return db.query(
            "SELECT r.id FROM bench_runs r JOIN bench_results res ON r.id = res.run_id "
                + "WHERE r.engine = ? AND r.scenario = ? AND r.concurrency = ? "
                + "AND r.id < ? GROUP BY r.id ORDER BY MAX(res.score) DESC LIMIT 1",
            candidate.engine(),
            candidate.scenario(),
            candidate.concurrency(),
            candidate.id())
        .one(Long.class);
  }
}
