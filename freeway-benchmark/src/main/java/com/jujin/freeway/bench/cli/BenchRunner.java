package com.jujin.freeway.bench.cli;

import com.jujin.freeway.benchmarks.ServerHarness;
import com.jujin.freeway.benchmarks.client.Http11Client;
import com.jujin.freeway.benchmarks.client.WsClient;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared benchmark execution logic used by {@link RunCommand} and {@link SuiteCommand}.
 */
public final class BenchRunner {

    private BenchRunner() {}

    /** Connection mode: keep-alive (reuse socket), short (new socket per request), or ws (WebSocket). */
    public enum Mode { KEEPALIVE, SHORT, WS }

    /** Result of a single benchmark iteration. */
    public record IterationResult(double rps, long p50us, long p95us, long p99us, int errors) {}

    /**
     * Runs a black-box HTTP benchmark against a server on the given port.
     *
     * @param port        server port
     * @param concurrency number of concurrent connections
     * @param requests    total requests to send
     * @param warmup      warmup requests sent before measurement
     * @param scenario    the server scenario (determines request path + expected response)
     * @param mode        keepalive, short, or ws connection mode
     * @return measurement results
     */
    public static IterationResult run(int port, int concurrency, int requests,
                                       int warmup, ServerHarness.Scenario scenario,
                                       Mode mode) throws Exception {
        if (mode == Mode.WS) {
            return runWs(port, concurrency, requests, warmup);
        }

        var pattern = switch (scenario) {
            case PING -> Http11Client.RequestPattern.PING;
            case JSON -> Http11Client.RequestPattern.JSON;
            case ECHO_BODY -> Http11Client.RequestPattern.PING;
            case WS_ECHO -> throw new IllegalArgumentException(
                "WS_ECHO requires Mode.WS");
        };

        // Warmup phase — send requests to let JIT settle
        if (warmup > 0) {
            warmupHttp(port, concurrency, warmup, pattern, mode);
        }

        // Measurement phase
        var latencies = new long[requests];
        var next = new AtomicInteger();
        var errs = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(concurrency);
        long t0 = System.nanoTime();
        try {
            var futures = new java.util.concurrent.Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                futures[t] = executor.submit(() -> {
                    if (mode == Mode.SHORT) {
                        // New connection per request
                        while (true) {
                            int i = next.getAndIncrement();
                            if (i >= requests) break;
                            try (var client = new Http11Client(port, pattern, true)) {
                                long ts = System.nanoTime();
                                if (client.send()) {
                                    latencies[i] = (System.nanoTime() - ts) / 1000L;
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
                                int i = next.getAndIncrement();
                                if (i >= requests) break;
                                long ts = System.nanoTime();
                                if (client.send()) {
                                    latencies[i] = (System.nanoTime() - ts) / 1000L;
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
            for (var f : futures) f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        int ok = requests - errs.get();
        var sorted = Arrays.copyOf(latencies, ok);
        Arrays.sort(sorted);
        double rps = ok * 1e9 / (System.nanoTime() - t0);
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);
        return new IterationResult(rps, p50, p95, p99, errs.get());
    }

    /**
     * Convenience: keep-alive mode (existing behavior).
     */
    public static IterationResult run(int port, int concurrency, int requests,
                                       int warmup, ServerHarness.Scenario scenario)
            throws Exception {
        return run(port, concurrency, requests, warmup, scenario, Mode.KEEPALIVE);
    }

    /**
     * Runs a WebSocket echo benchmark.
     * Each connection sends one text frame, measures round-trip time to echo.
     */
    private static IterationResult runWs(int port, int concurrency, int requests,
                                         int warmup) throws Exception {
        // Warmup phase
        if (warmup > 0) {
            warmupWs(port, concurrency, warmup);
        }

        var latencies = new long[requests];
        var next = new AtomicInteger();
        var errs = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(concurrency);
        long t0 = System.nanoTime();
        try {
            var futures = new java.util.concurrent.Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                futures[t] = executor.submit(() -> {
                    while (true) {
                        int i = next.getAndIncrement();
                        if (i >= requests) break;
                        try (var client = new WsClient(port)) {
                            long nanos = client.echo("hello");
                            if (nanos > 0) {
                                latencies[i] = nanos / 1000L;
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
            for (var f : futures) f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        int ok = requests - errs.get();
        var sorted = Arrays.copyOf(latencies, ok);
        Arrays.sort(sorted);
        double rps = ok * 1e9 / (System.nanoTime() - t0);
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);
        return new IterationResult(rps, p50, p95, p99, errs.get());
    }

    /** Warmup for HTTP: send requests, discard results. */
    private static void warmupHttp(int port, int concurrency, int requests,
                                    Http11Client.RequestPattern pattern,
                                    Mode mode) throws Exception {
        var count = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(concurrency);
        try {
            var futures = new java.util.concurrent.Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                futures[t] = executor.submit(() -> {
                    if (mode == Mode.SHORT) {
                        while (true) {
                            int i = count.getAndIncrement();
                            if (i >= requests) break;
                            try (var client = new Http11Client(port, pattern, true)) {
                                client.send();
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        try (var client = new Http11Client(port, pattern)) {
                            while (true) {
                                int i = count.getAndIncrement();
                                if (i >= requests) break;
                                client.send();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    return null;
                });
            }
            for (var f : futures) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Warmup for WebSocket: send frames, discard results. */
    private static void warmupWs(int port, int concurrency, int requests) throws Exception {
        var count = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(concurrency);
        try {
            var futures = new java.util.concurrent.Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                futures[t] = executor.submit(() -> {
                    while (true) {
                        int i = count.getAndIncrement();
                        if (i >= requests) break;
                        try (var client = new WsClient(port)) {
                            client.echo("warmup");
                        } catch (Exception ignored) {
                        }
                    }
                    return null;
                });
            }
            for (var f : futures) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Computes the median of a sorted array at the given fraction.
     */
    static long percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) return 0;
        return sorted[(int) Math.min(
            Math.ceil(sorted.length * fraction) - 1, sorted.length - 1)];
    }

    /**
     * Computes population standard deviation from an array of scores.
     */
    static double stddev(double[] values) {
        if (values.length <= 1) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        double mean = sum / values.length;
        double sqSum = 0;
        for (double v : values) sqSum += (v - mean) * (v - mean);
        return Math.sqrt(sqSum / values.length);
    }
}
