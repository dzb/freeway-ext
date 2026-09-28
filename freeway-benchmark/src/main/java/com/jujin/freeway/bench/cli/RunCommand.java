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
import com.jujin.freeway.bench.harness.ScenarioSpec;
import com.jujin.freeway.bench.harness.ServerHarness;
import com.jujin.freeway.bench.run.BenchMode;
import com.jujin.freeway.bench.run.BenchRunner;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.event.EventBus;
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
 * --median-last=0       representative round = median of the last N rounds (0 = all)
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
    int concurrency = ctx.getInt("concurrency", 32);
    int requests = ctx.getInt("requests", 5000);
    int warmup = ctx.getInt("warmup", 2_000);
    int runs = ctx.getInt("runs", 5);
    int medianLast = ctx.getInt("median-last", 0);

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
    if (ScenarioSpec.of(scn).echoBody()) {
      // The load client cannot send a request body in any mode — failing here keeps
      // a broken cell from booting a server, burning rounds, and persisting zeros.
      throw new UsageException(
          "Scenario ECHO_BODY is not measurable: the client cannot send a request body");
    }

    // The window is part of what was measured, so it belongs in the run's own record. A zero
    // window is the default and stays out of the line (the suite prints its whole option map).
    System.out.printf(
        "bench run --engine=%s --scenario=%s --concurrency=%d "
            + "--requests=%d --warmup=%d --runs=%d --mode=%s%s%n",
        engine,
        scenario,
        concurrency,
        requests,
        warmup,
        runs,
        modeLabel,
        medianLast > 0 ? " --median-last=" + medianLast : "");

    var container = ctx.container();
    var repository = container.get(BenchRepository.class);
    var eventBus = container.get(EventBus.class);

    // One cell: its rows, its rounds, and its representative-round arithmetic all live here.
    var cell =
        BenchCell.open(
            repository, eventBus, engine, scenario, concurrency, requests, warmup, runs, modeLabel);

    try (var harness = ServerHarness.start(eng, scn)) {
      int port = harness.port();

      for (int r = 0; r < runs; r++) {
        System.out.printf("  run %d/%d ...%n", r + 1, runs);
        var ir = BenchRunner.run(port, concurrency, requests, warmup, scn, mode.clientMode());
        cell.collect(r, ir);

        System.out.printf(
            "    rps=%s p50=%s p95=%s p99=%s errors=%d%n",
            BenchFormat.rps(ir.rps()),
            BenchFormat.micros(ir.p50us()),
            BenchFormat.micros(ir.p95us()),
            BenchFormat.micros(ir.p99us()),
            ir.errors());
      }
    }

    int medianIndex = cell.finish(medianLast);
    double avgRps = cell.averageRps();
    double error = cell.dispersion();

    eventBus.publish(new BenchEvent.RunCompleted(cell.runId()));
    System.out.printf(
        "Done. Run #%d saved. avg=%s ± %s req/s%n",
        cell.runId(), BenchFormat.rps(avgRps), BenchFormat.rps(error));

    // Print summary table
    var rounds = cell.rounds();
    var rows = new ArrayList<BenchFormat.Row>();
    for (int i = 0; i < rounds.size(); i++) {
      var r = rounds.get(i);
      rows.add(
          BenchFormat.Row.of(
              String.valueOf(i + 1),
              BenchFormat.rps(r.rps()),
              BenchFormat.micros(r.p50us()),
              BenchFormat.micros(r.p95us()),
              BenchFormat.micros(r.p99us()),
              String.valueOf(r.errors())));
    }
    if (rounds.size() > 1) {
      var median = rounds.get(medianIndex);
      rows.add(
          BenchFormat.Row.of(
                  "Median",
                  BenchFormat.rps(median.rps()),
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

    // Broken-cell and unsteady-cell gates: a zero row is failed infrastructure, and a
    // climbing tail means warmup was too short — both print loudly, only the former fails.
    cell.requireMeasured();
    if (cell.climbing()) {
      System.out.println("WARN: rounds still climbing — warmup insufficient, median under-reports");
    }

    // Write JSON output if --output is specified
    String outputPath = ctx.get("output", null);
    if (outputPath != null && !outputPath.isBlank()) {
      BenchFormat.requireOutputExtension(outputPath, ".json", "JSON");
      var jsonMap = new LinkedHashMap<String, Object>();
      jsonMap.put("run_id", cell.runId());
      jsonMap.put("engine", engine);
      jsonMap.put("scenario", scenario);
      jsonMap.put("concurrency", concurrency);
      jsonMap.put("requests", requests);
      jsonMap.put("warmup", warmup);
      jsonMap.put("runs", runs);
      jsonMap.put("avg_rps", avgRps);
      jsonMap.put("stddev_rps", error);
      jsonMap.put("mode", modeLabel);
      var runRow = repository.findRun(cell.runId()).orElseThrow();
      jsonMap.put("commit_sha", runRow.commitSha());
      jsonMap.put("dirty_files", runRow.dirtyFiles());
      jsonMap.put("jdk_info", runRow.jdkInfo());
      jsonMap.put("cpu_info", runRow.cpuInfo());
      jsonMap.put("jvm_flags", runRow.jvmFlags());
      jsonMap.put("heap_max_mb", runRow.heapMaxMb());

      var runsList = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < rounds.size(); i++) {
        var r = rounds.get(i);
        var m = new LinkedHashMap<String, Object>();
        m.put("run", i + 1);
        m.put("rps", r.rps());
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
