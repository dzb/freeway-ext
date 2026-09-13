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

import com.jujin.freeway.bench.event.BenchEvent;
import com.jujin.freeway.bench.harness.ServerHarness;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.bench.run.BenchMode;
import com.jujin.freeway.bench.run.BenchRunner;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import com.jujin.freeway.ioc.EventBus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code bench run} — runs a black-box HTTP or WebSocket benchmark and persists results.
 *
 * <p>Arguments:
 *
 * <pre>
 * --engine=freeway      server engine
 * --scenario=ping       benchmark scenario
 * --concurrency=32      concurrent connections
 * --requests=5000       requests per run
 * --warmup=500          warmup requests before measurement
 * --runs=3              number of measurement runs
 * --mode=keepalive      connection mode: keepalive, short, or ws
 * --output=results.json optional: write JSON results to file
 * </pre>
 */
public final class RunCommand implements Command {

  @Override
  public String name() {
    return "run";
  }

  @Override
  public void run(Context ctx) throws Exception {
    var engine = ctx.get("engine", "freeway");
    var scenario = ctx.get("scenario", "ping");
    var modeStr = ctx.get("mode", "keepalive");
    int concurrency = ctx.getInt("concurrency", 32);
    int requests = ctx.getInt("requests", 5000);
    int warmup = ctx.getInt("warmup", 500);
    int runs = ctx.getInt("runs", 3);

    // Resolve the engine/scenario before anything is written: a usage error must
    // not leave a half-created run row behind.
    var eng = ctx.parse("engine", ServerHarness.Engine::fromString, ServerHarness.Engine.FREEWAY);
    var scn =
        ctx.parse(
            "scenario",
            value -> ServerHarness.Scenario.valueOf(value.toUpperCase(Locale.ROOT)),
            ServerHarness.Scenario.PING);

    var mode = ctx.parse("mode", BenchMode::of, BenchMode.DEFAULT);
    var modeLabel = mode.label();

    System.out.printf(
        "bench run --engine=%s --scenario=%s --concurrency=%d "
            + "--requests=%d --warmup=%d --runs=%d --mode=%s%n",
        engine, scenario, concurrency, requests, warmup, runs, modeLabel);

    // Retrieve Database from container (provided by BenchDbModule)
    var container = ctx.container();
    var db = container.get(Database.class);
    var coercer = container.get(Coercer.class);
    var orm = new Orm(db, coercer);
    var eventBus = container.get(EventBus.class);

    // Create run record
    var run = BenchmarkRun.create(engine, scenario, concurrency, requests, warmup, runs);
    long runId = orm.insert(run).longKey();
    eventBus.publish(new BenchEvent.RunStarted(run));

    // Run the benchmark
    var results = new ArrayList<BenchmarkResult>();
    var iterations = new ArrayList<BenchRunner.IterationResult>();
    var resultIds = new long[runs];
    var scores = new double[runs];

    try (var harness = ServerHarness.start(eng, scn)) {
      int port = harness.port();

      for (int r = 0; r < runs; r++) {
        System.out.printf("  run %d/%d ...%n", r + 1, runs);
        var ir = BenchRunner.run(port, concurrency, requests, warmup, scn, mode.clientMode());
        scores[r] = ir.rps();

        System.out.printf(
            "    rps=%s p50=%s p95=%s p99=%s errors=%d%n",
            BenchFormat.rps(ir.rps()),
            BenchFormat.micros(ir.p50us()),
            BenchFormat.micros(ir.p95us()),
            BenchFormat.micros(ir.p99us()),
            ir.errors());

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
        results.add(result);
        iterations.add(ir);
        // The generated key is the id of this row: capturing it here is what
        // removes the re-query that used to hunt the median row afterwards.
        resultIds[r] = orm.insert(result).longKey();
        eventBus.publish(new BenchEvent.ResultCollected(result));
      }
    }

    // Compute score_error as stddev across all runs
    double avgRps = 0;
    for (double s : scores) avgRps += s;
    avgRps /= runs;
    int medianIndex = BenchRunner.medianIndex(iterations);
    double error = runs > 1 ? BenchRunner.stddev(scores) : 0;
    if (runs > 1) {
      // The dispersion belongs on the row every comparison prints: the median
      // iteration. Its id came from the insert above.
      db.execute(
          "UPDATE bench_results SET score_error = ? WHERE id = ?", error, resultIds[medianIndex]);
    }

    eventBus.publish(new BenchEvent.RunCompleted(runId));
    System.out.printf(
        "Done. Run #%d saved. avg=%s ± %s req/s%n",
        runId, BenchFormat.rps(avgRps), BenchFormat.rps(error));

    // Print summary table
    var rows = new ArrayList<BenchFormat.Row>();
    for (int i = 0; i < results.size(); i++) {
      var r = results.get(i);
      rows.add(
          BenchFormat.Row.of(
              String.valueOf(i + 1),
              BenchFormat.rps(r.score()),
              BenchFormat.micros(r.p50us()),
              BenchFormat.micros(r.p95us()),
              BenchFormat.micros(r.p99us()),
              String.valueOf(r.errors())));
    }
    if (results.size() > 1) {
      var median = results.get(medianIndex);
      rows.add(
          BenchFormat.Row.of(
                  "Median",
                  BenchFormat.rps(median.score()),
                  BenchFormat.micros(median.p50us()),
                  BenchFormat.micros(median.p95us()),
                  BenchFormat.micros(median.p99us()),
                  String.valueOf(median.errors()))
              .asBold());
    }
    System.out.println();
    System.out.println(
        BenchFormat.table(
            List.of("Run", "RPS", "p50", "p95", "p99", "Errors"),
            List.of(
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.RIGHT),
            rows));

    // Write JSON output if --output is specified
    String outputPath = ctx.get("output", null);
    if (outputPath != null && !outputPath.isBlank()) {
      BenchFormat.requireOutputExtension(outputPath, ".json", "JSON");
      var jsonMap = new LinkedHashMap<String, Object>();
      jsonMap.put("run_id", runId);
      jsonMap.put("engine", engine);
      jsonMap.put("scenario", scenario);
      jsonMap.put("concurrency", concurrency);
      jsonMap.put("requests", requests);
      jsonMap.put("warmup", warmup);
      jsonMap.put("runs", runs);
      jsonMap.put("avg_rps", avgRps);
      jsonMap.put("stddev_rps", error);
      jsonMap.put("mode", modeLabel);

      var runsList = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < results.size(); i++) {
        var r = results.get(i);
        var m = new LinkedHashMap<String, Object>();
        m.put("run", i + 1);
        m.put("rps", r.score());
        m.put("p50_us", r.p50us());
        m.put("p95_us", r.p95us());
        m.put("p99_us", r.p99us());
        m.put("errors", r.errors());
        runsList.add(m);
      }
      jsonMap.put("results", runsList);

      var json = new JsonCodecDefault().toJson(jsonMap);
      Files.writeString(Path.of(outputPath), json, StandardCharsets.UTF_8);
      System.out.println("Results written to " + outputPath);
    }
  }
}
