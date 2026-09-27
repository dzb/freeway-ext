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

package com.jujin.freeway.bench.run;

import com.jujin.freeway.bench.client.Http11Client;
import com.jujin.freeway.bench.client.WsClient;
import com.jujin.freeway.bench.harness.ServerHarness;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared benchmark execution logic used by {@link RunCommand} and {@link SuiteCommand}. */
public final class BenchRunner {

  private BenchRunner() {}

  /**
   * Connection mode: keep-alive (reuse socket), short (new socket per request), or ws (WebSocket).
   */
  public enum Mode {
    KEEPALIVE,
    SHORT,
    WS
  }

  /** Result of a single benchmark iteration. */
  public record IterationResult(
      double rps, long p50us, long p95us, long p99us, int errors, boolean saturated) {}

  /**
   * Client process CPU above which a round is mistrusted: the generator, not the server, was the
   * bottleneck. Only meaningful when the generator runs alone (split shape) — sharing a JVM with
   * the server (smoke shape) reports both sides at once.
   */
  static final double SATURATED_CPU = 0.75;

  /**
   * A cell whose rounds produced nothing measurable is broken infrastructure, not a slow server —
   * fail loudly instead of persisting a zero row that later reads as data.
   */
  public static boolean totalFailure(List<IterationResult> rs) {
    return !rs.isEmpty() && rs.stream().allMatch(ir -> ir.rps() == 0);
  }

  /**
   * True when the tail still climbs: max/min spread over the last (up to) five rounds exceeds a
   * quarter. A climbing cell has not reached steady state — its median under-reports, so warmup
   * goes up, not the round count.
   */
  public static boolean stillClimbing(List<IterationResult> rs) {
    int n = Math.min(rs.size(), 5);
    if (n < 2) {
      return false;
    }
    var tail = rs.subList(rs.size() - n, rs.size());
    // A zero round is broken infrastructure (already reported by totalFailure and by that row's
    // error count), not a warmup ramp: treating it as the minimum would warn on every partial
    // failure, which is the opposite of a useful signal.
    double min = Double.MAX_VALUE;
    double max = 0;
    int measured = 0;
    for (var ir : tail) {
      if (ir.rps() <= 0) {
        continue;
      }
      measured++;
      min = Math.min(min, ir.rps());
      max = Math.max(max, ir.rps());
    }
    return measured >= 2 && max > min * 1.25;
  }

  /**
   * Client process CPU load 0..1 since the previous sample — primed before warmup and read after
   * the measurement, so one round's reading covers warmup plus measurement (the JIT ramp is CPU the
   * generator also spent). -1 when the platform withholds it.
   */
  static double processCpu() {
    var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
      return sun.getProcessCpuLoad();
    }
    return -1;
  }

  /**
   * The index of the run's representative iteration: the median by rps, the same row the summary
   * tables print and the one the run-level dispersion is recorded on. Both commands pick it here so
   * "the median" cannot mean two things.
   *
   * <p>Its companion is {@link Result#median(List, int)}, the per-cell line a forked run prints: it
   * medians each field and so reports robust tails, but over the same window and with the same
   * {@code size/2} convention, so the rps the two report is the same number.
   */
  public static int medianIndex(List<IterationResult> results) {
    return medianIndex(results, 0);
  }

  /**
   * The same median restricted to the last {@code window} rounds, index still into {@code results}.
   *
   * <p>A window is how the standing protocol reads a cell: with warmup short relative to the JIT
   * ramp the first rounds measure warmup, not the engine ({@link #stillClimbing} warns about
   * exactly that), so the tail is the signal. {@code window <= 0} or {@code window >= size} means
   * every round — the pre-flag behavior.
   */
  public static int medianIndex(List<IterationResult> results, int window) {
    if (results.isEmpty()) {
      throw new IllegalArgumentException("no iterations to take a median from");
    }
    int from = window > 0 ? Math.max(0, results.size() - window) : 0;
    return java.util.stream.IntStream.range(from, results.size())
        .boxed()
        .sorted(Comparator.comparingDouble((Integer i) -> results.get(i).rps()))
        .toList()
        .get((results.size() - from) / 2);
  }

  /**
   * Runs a black-box HTTP benchmark against a server on the given port.
   *
   * @param port server port
   * @param concurrency number of concurrent connections
   * @param requests total requests to send
   * @param warmup warmup requests sent before measurement
   * @param scenario the server scenario (determines request path + expected response)
   * @param mode keepalive, short, or ws connection mode
   * @return measurement results
   */
  public static IterationResult run(
      int port,
      int concurrency,
      int requests,
      int warmup,
      ServerHarness.Scenario scenario,
      Mode mode)
      throws Exception {
    // Prime the process-CPU counter; the closing sample covers warmup plus measurement.
    processCpu();
    if (mode == Mode.WS) {
      if (scenario != ServerHarness.Scenario.WS_ECHO) {
        throw new IllegalArgumentException(
            "--mode=ws requires --scenario=ws_echo, got: " + scenario);
      }
      return runWs(port, concurrency, requests, warmup);
    }

    var spec = com.jujin.freeway.bench.harness.ScenarioSpec.of(scenario);
    if (spec.webSocket()) {
      throw new IllegalArgumentException("Scenario " + scenario + " requires --mode=ws");
    }
    // Http11Client cannot send a request body, so the POST /echo scenario cannot
    // be measured; fail fast instead of silently benchmarking GET /ping under
    // the echo_body label.
    if (spec.echoBody()) {
      throw new IllegalArgumentException(
          "Scenario " + scenario + " is not supported (client cannot send a request body)");
    }
    var pattern = Http11Client.RequestPattern.of(spec);

    // Warmup phase — send requests to let JIT settle
    if (warmup > 0) {
      warmupHttp(port, concurrency, warmup, pattern, mode);
    }

    // Measurement phase
    // Only successful requests contribute latency samples. Using a dedicated
    // success counter avoids zero-filled failure slots skewing percentiles.
    var okLatencies = new long[requests];
    var okCount = new AtomicInteger();
    var next = new AtomicInteger();
    var errs = new AtomicInteger();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    long t0 = System.nanoTime();
    try {
      var futures = new Future<?>[concurrency];
      for (int t = 0; t < concurrency; t++) {
        futures[t] =
            executor.submit(
                () -> {
                  if (mode == Mode.SHORT) {
                    // New connection per request
                    while (true) {
                      if (next.getAndIncrement() >= requests) break;
                      try (var client = new Http11Client(port, pattern, true)) {
                        long ts = System.nanoTime();
                        if (client.send()) {
                          okLatencies[okCount.getAndIncrement()] = (System.nanoTime() - ts) / 1000L;
                        } else {
                          errs.incrementAndGet();
                        }
                      } catch (Exception e) {
                        errs.incrementAndGet();
                      }
                    }
                  } else {
                    // Reuse one connection per thread
                    try (var client = new Http11Client(port, pattern)) {
                      while (true) {
                        if (next.getAndIncrement() >= requests) break;
                        long ts = System.nanoTime();
                        if (client.send()) {
                          okLatencies[okCount.getAndIncrement()] = (System.nanoTime() - ts) / 1000L;
                        } else {
                          errs.incrementAndGet();
                        }
                      }
                    } catch (Exception e) {
                      errs.incrementAndGet();
                    }
                  }
                  return null;
                });
      }
      try {
        for (var f : futures) f.get(120, TimeUnit.SECONDS);
      } catch (TimeoutException ex) {
        // Requests that missed the deadline never completed; count them
        // as errors so the reported total stays truthful.
        errs.addAndGet(Math.max(0, requests - okCount.get() - errs.get()));
      }
    } finally {
      executor.shutdownNow();
    }

    int ok = okCount.get();
    var sorted = Arrays.copyOf(okLatencies, ok);
    Arrays.sort(sorted);
    double rps = ok * 1e9 / (System.nanoTime() - t0);
    long p50 = percentile(sorted, 0.50);
    long p95 = percentile(sorted, 0.95);
    long p99 = percentile(sorted, 0.99);
    return new IterationResult(rps, p50, p95, p99, errs.get(), processCpu() >= SATURATED_CPU);
  }

  /** Convenience: keep-alive mode (existing behavior). */
  public static IterationResult run(
      int port, int concurrency, int requests, int warmup, ServerHarness.Scenario scenario)
      throws Exception {
    return run(port, concurrency, requests, warmup, scenario, Mode.KEEPALIVE);
  }

  /**
   * Runs a WebSocket echo benchmark. Each connection sends one text frame, measures round-trip time
   * to echo.
   */
  private static IterationResult runWs(int port, int concurrency, int requests, int warmup)
      throws Exception {
    processCpu();
    // Warmup phase
    if (warmup > 0) {
      warmupWs(port, concurrency, warmup);
    }

    var okLatencies = new long[requests];
    var okCount = new AtomicInteger();
    var next = new AtomicInteger();
    var errs = new AtomicInteger();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    long t0 = System.nanoTime();
    try {
      var futures = new Future<?>[concurrency];
      for (int t = 0; t < concurrency; t++) {
        futures[t] =
            executor.submit(
                () -> {
                  while (true) {
                    if (next.getAndIncrement() >= requests) break;
                    try (var client = new WsClient(port)) {
                      long nanos = client.echo("hello");
                      if (nanos > 0) {
                        okLatencies[okCount.getAndIncrement()] = nanos / 1000L;
                      } else {
                        errs.incrementAndGet();
                      }
                    } catch (Exception e) {
                      errs.incrementAndGet();
                    }
                  }
                  return null;
                });
      }
      try {
        for (var f : futures) f.get(120, TimeUnit.SECONDS);
      } catch (TimeoutException ex) {
        errs.addAndGet(Math.max(0, requests - okCount.get() - errs.get()));
      }
    } finally {
      executor.shutdownNow();
    }

    int ok = okCount.get();
    var sorted = Arrays.copyOf(okLatencies, ok);
    Arrays.sort(sorted);
    double rps = ok * 1e9 / (System.nanoTime() - t0);
    long p50 = percentile(sorted, 0.50);
    long p95 = percentile(sorted, 0.95);
    long p99 = percentile(sorted, 0.99);
    return new IterationResult(rps, p50, p95, p99, errs.get(), processCpu() >= SATURATED_CPU);
  }

  /** Warmup for HTTP: send requests, discard results. */
  private static void warmupHttp(
      int port, int concurrency, int requests, Http11Client.RequestPattern pattern, Mode mode)
      throws Exception {
    var count = new AtomicInteger();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var futures = new Future<?>[concurrency];
      for (int t = 0; t < concurrency; t++) {
        futures[t] =
            executor.submit(
                () -> {
                  if (mode == Mode.SHORT) {
                    while (true) {
                      if (count.getAndIncrement() >= requests) break;
                      try (var client = new Http11Client(port, pattern, true)) {
                        client.send();
                      } catch (Exception ignored) {
                      }
                    }
                  } else {
                    try (var client = new Http11Client(port, pattern)) {
                      while (true) {
                        if (count.getAndIncrement() >= requests) break;
                        client.send();
                      }
                    } catch (Exception ignored) {
                    }
                  }
                  return null;
                });
      }
      for (var f : futures) f.get(60, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Warmup for WebSocket: send frames, discard results. */
  private static void warmupWs(int port, int concurrency, int requests) throws Exception {
    var count = new AtomicInteger();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var futures = new Future<?>[concurrency];
      for (int t = 0; t < concurrency; t++) {
        futures[t] =
            executor.submit(
                () -> {
                  while (true) {
                    if (count.getAndIncrement() >= requests) break;
                    try (var client = new WsClient(port)) {
                      client.echo("warmup");
                    } catch (Exception ignored) {
                    }
                  }
                  return null;
                });
      }
      for (var f : futures) f.get(60, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Computes the median of a sorted array at the given fraction. */
  static long percentile(long[] sorted, double fraction) {
    if (sorted.length == 0) return 0;
    return sorted[(int) Math.min(Math.ceil(sorted.length * fraction) - 1, sorted.length - 1)];
  }

  /** Computes population standard deviation from an array of scores. */
  public static double stddev(double[] values) {
    if (values.length <= 1) return 0;
    double sum = 0;
    for (double v : values) sum += v;
    double mean = sum / values.length;
    double sqSum = 0;
    for (double v : values) sqSum += (v - mean) * (v - mean);
    return Math.sqrt(sqSum / values.length);
  }
}
