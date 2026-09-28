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
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.bench.run.BenchRunner;
import com.jujin.freeway.ioc.event.EventBus;
import java.util.ArrayList;
import java.util.List;

/**
 * One measured cell's bookkeeping: its run row, every round's persisted result, and the
 * representative-round arithmetic (median by rps, dispersion recorded on that same row). Both
 * commands that measure a cell — {@code run} (one cell) and {@code suite} (many, in either the
 * in-JVM or the forked shape) — go through this type, so what a cell records cannot come to mean
 * one thing on one path and something else on another.
 *
 * <p>Not a value: it wraps inserts, and a round that throws from {@link #collect} leaves the rows
 * already written in place — which is what the run row is for.
 */
final class BenchCell {

  private final BenchRepository repository;
  private final EventBus eventBus;
  private final long runId;

  /** The cell's identity in a row: {@code engine/scenario}. */
  private final String name;

  private final String modeLabel;
  private final int runs;
  private final double[] scores;
  private final long[] resultIds;
  private final List<BenchRunner.IterationResult> rounds = new ArrayList<>();

  private BenchCell(
      BenchRepository repository,
      EventBus eventBus,
      long runId,
      String name,
      String modeLabel,
      int runs) {
    this.repository = repository;
    this.eventBus = eventBus;
    this.runId = runId;
    this.name = name;
    this.modeLabel = modeLabel;
    this.runs = runs;
    this.scores = new double[runs];
    this.resultIds = new long[runs];
  }

  /** Opens the cell: the run row is written and announced before the first round runs. */
  static BenchCell open(
      BenchRepository repository,
      EventBus eventBus,
      String engine,
      String scenario,
      int concurrency,
      int requests,
      int warmup,
      int runs,
      String modeLabel) {
    var run = BenchmarkRun.create(engine, scenario, concurrency, requests, warmup, runs);
    long runId = repository.insertRun(run);
    eventBus.publish(new BenchEvent.RunStarted(run));
    return new BenchCell(repository, eventBus, runId, engine + "/" + scenario, modeLabel, runs);
  }

  long runId() {
    return runId;
  }

  /**
   * Records one round: the measurement in memory, its row in the database, and the event that makes
   * it visible to listeners — the three always happen together.
   */
  void collect(int index, BenchRunner.IterationResult round) {
    scores[index] = round.rps();
    rounds.add(round);
    var result =
        BenchmarkResult.forHttpIteration(
            runId,
            name,
            modeLabel,
            round.rps(),
            round.p50us(),
            round.p95us(),
            round.p99us(),
            round.errors());
    resultIds[index] = repository.insertResult(result);
    eventBus.publish(new BenchEvent.ResultCollected(result));
  }

  /** The rounds in order, as measured. */
  List<BenchRunner.IterationResult> rounds() {
    return List.copyOf(rounds);
  }

  double averageRps() {
    double sum = 0;
    for (double score : scores) {
      sum += score;
    }
    return sum / runs;
  }

  /** Spread across rounds; 0 for a single-round cell (dispersion needs two points). */
  double dispersion() {
    return runs > 1 ? BenchRunner.stddev(scores) : 0;
  }

  /**
   * Picks the representative round — the median by rps within the window, the same index the
   * comparison commands read — and records the dispersion on that very row, so "typical ± spread"
   * is one row's worth of data rather than two rows a reader has to know to pair.
   */
  int finish(int window) {
    int median = BenchRunner.medianIndex(rounds, window);
    if (runs > 1) {
      repository.recordDispersion(resultIds[median], dispersion());
    }
    return median;
  }

  /** A cell whose rounds all produced nothing is broken infrastructure, not a slow server. */
  void requireMeasured() {
    if (BenchRunner.totalFailure(rounds)) {
      throw new IllegalStateException("Cell " + name + " produced no successful requests");
    }
  }

  /**
   * Whether the tail still climbs — warmup too short for the JIT ramp, so the median under-reports.
   */
  boolean climbing() {
    return BenchRunner.stillClimbing(rounds);
  }
}
