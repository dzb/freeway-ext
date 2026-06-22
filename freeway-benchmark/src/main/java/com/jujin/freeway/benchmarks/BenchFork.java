package com.jujin.freeway.benchmarks;

import com.jujin.freeway.bench.cli.BenchRunner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Isolated benchmark runner using separate JVM processes for server and client.
 * Each measurement run spawns a fresh server + client pair — zero cross-contamination.
 *
 * <p>Use this for final performance claims where process-level isolation matters.
 * For day-to-day iteration, use the CLI ({@code bench run}) instead.
 *
 * <pre>
 * mvn -f freeway-benchmark/pom.xml -am process-classes
 * mvn -f freeway-benchmark/pom.xml exec:java \
 *   -Dexec.mainClass=com.jujin.freeway.benchmarks.BenchFork \
 *   -Dbench.engine=freeway -Dbench.mode=keepalive \
 *   -Dbench.requests=20000 -Dbench.concurrency=32 -Dbench.runs=3
 * </pre>
 */
public final class BenchFork {

    private static final String MAIN_CLASS = "com.jujin.freeway.benchmarks.BenchFork";

    public static void main(String[] args) throws Exception {
        String role = p("bench.role", "suite");
        if ("server".equalsIgnoreCase(role)) {
            runServer(p("bench.engine", "freeway"), p("bench.mode", "keepalive"));
            return;
        }
        if ("client".equalsIgnoreCase(role)) {
            runClient(p("bench.engine", "freeway"), p("bench.mode", "keepalive"),
                ip("bench.port", 0), ip("bench.requests", 2000),
                ip("bench.concurrency", 2), ip("bench.warmup", 200));
            return;
        }

        // Suite mode: orchestrate fork cycles
        String engine = p("bench.engine", "freeway");
        String mode = p("bench.mode", "keepalive");
        int requests = ip("bench.requests", 20_000);
        int concurrency = ip("bench.concurrency",
            Math.max(1, Runtime.getRuntime().availableProcessors()));
        int warmup = ip("bench.warmup", 2_000);
        int runs = ip("bench.runs", 3);
        int pause = ip("bench.pauseMillis", 3000);

        System.out.printf("=== %s mode=%s requests=%d concurrency=%d runs=%d ===%n",
            engine, mode, requests, concurrency, runs);

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            Result r = runFork(engine, mode, requests, concurrency, warmup, i);
            results.add(r);
            System.out.printf("[run %d/%d] %s%n", i + 1, runs, r);
            if (i + 1 < runs && pause > 0) Thread.sleep(pause);
        }
        if (runs > 1) System.out.printf("[median] %s%n", Result.median(results));
    }

    // --- fork orchestration ---

    private static Result runFork(String engine, String mode, int requests,
                                   int concurrency, int warmup, int runIdx) throws Exception {
        Path serverLog = Files.createTempFile("bench-server-", ".log");
        Process server = new ProcessBuilder(javaBin(), "-cp", classpath(),
            "-Dbench.role=server", "-Dbench.engine=" + engine,
            "-Dbench.mode=" + mode, MAIN_CLASS)
            .redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        try {
            int port = awaitReady(server, serverLog, Duration.ofSeconds(30));
            Path clientLog = Files.createTempFile("bench-client-", ".log");
            Process client = new ProcessBuilder(javaBin(), "-cp", classpath(),
                "-Dbench.role=client", "-Dbench.engine=" + engine,
                "-Dbench.mode=" + mode, "-Dbench.port=" + port,
                "-Dbench.requests=" + requests, "-Dbench.concurrency=" + concurrency,
                "-Dbench.warmup=" + warmup, MAIN_CLASS)
                .redirectErrorStream(true).redirectOutput(clientLog.toFile()).start();
            try {
                int exit = client.waitFor();
                if (exit != 0)
                    throw new RuntimeException("Client exit " + exit
                        + "\n" + readAllSafe(clientLog));
                return parseResult(readAllSafe(clientLog));
            } finally {
                client.destroyForcibly();
                deleteSafe(clientLog);
            }
        } finally {
            server.destroyForcibly();
            deleteSafe(serverLog);
        }
    }

    // --- server / client subprocess entry points ---

    private static void runServer(String engine, String mode) throws Exception {
        var eng = ServerHarness.Engine.fromString(engine);
        try (var h = ServerHarness.start(eng, ServerHarness.Scenario.PING)) {
            System.out.println("READY port=" + h.port() + " engine=" + engine);
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    private static void runClient(String engine, String mode, int port,
                                   int requests, int concurrency, int warmup)
            throws Exception {
        var benchMode = resolveMode(mode);
        var ir = BenchRunner.run(port, concurrency, requests, warmup,
            ServerHarness.Scenario.PING, benchMode);
        var r = toResult(engine, mode, requests, ir);
        System.out.println("RESULT " + r);
    }

    // --- helpers ---

    private static int awaitReady(Process p, Path log, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(log)) {
                try {
                    String content = new String(Files.readAllBytes(log),
                        StandardCharsets.ISO_8859_1);
                    for (String line : content.lines().toList()) {
                        if (line.startsWith("READY ")) {
                            String port = line.substring(
                                line.indexOf('=') + 1).split("\\s")[0];
                            return Integer.parseInt(port);
                        }
                    }
                } catch (IOException ignored) {}
            }
            if (!p.isAlive())
                throw new RuntimeException("Server died: " + readAllSafe(log));
            Thread.sleep(100);
        }
        throw new RuntimeException("Server not ready: " + readAllSafe(log));
    }

    private static String readAllSafe(Path log) {
        try {
            return new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    private static Result parseResult(String output) {
        for (String line : output.lines().toList())
            if (line.startsWith("RESULT ")) return Result.fromLine(line);
        throw new RuntimeException("No RESULT line in output:\n" + output);
    }

    private static BenchRunner.Mode resolveMode(String mode) {
        if ("ws".equalsIgnoreCase(mode) || "websocket".equalsIgnoreCase(mode))
            return BenchRunner.Mode.WS;
        if ("short".equalsIgnoreCase(mode)) return BenchRunner.Mode.SHORT;
        return BenchRunner.Mode.KEEPALIVE;
    }

    private static Result toResult(String engine, String mode, int requests,
                                    BenchRunner.IterationResult ir) {
        return new Result(engine, mode, requests,
            requests - ir.errors(), ir.errors(),
            ir.rps(), ir.p50us(), ir.p95us(), ir.p99us());
    }

    private static String p(String k, String d) {
        String v = System.getProperty(k);
        return v != null && !v.isBlank() ? v : d;
    }

    private static int ip(String k, int d) {
        String v = System.getProperty(k);
        return v == null || v.isBlank() ? d : Integer.parseInt(v.trim());
    }

    private static String javaBin() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static void deleteSafe(Path file) {
        for (int i = 0; i < 5; i++) {
            try { Files.deleteIfExists(file); return; } catch (IOException e) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }
    }

    private static String classpath() {
        String override = System.getProperty("bench.classpath");
        if (override != null && !override.isBlank()) return override;
        for (String p : List.of(
            "freeway-benchmark/target/benchmark.classpath",
            "target/benchmark.classpath")) {
            Path f = Path.of(p);
            if (Files.isRegularFile(f)) {
                try {
                    String deps = Files.readString(f).trim();
                    String classes = f.getParent().resolve("classes").toString();
                    return classes + File.pathSeparator + deps;
                } catch (IOException ignored) {}
            }
        }
        return System.getProperty("java.class.path");
    }
}
