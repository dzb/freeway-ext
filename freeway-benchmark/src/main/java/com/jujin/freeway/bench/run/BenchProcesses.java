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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Process plumbing for the forked benchmark: where the JVM is, which classpath the child must get,
 * how a child reports readiness, and how its log file is read and cleaned up. Kept apart from the
 * suite orchestration so the classpath/readiness rules have one home and can be tested without
 * spawning anything.
 */
final class BenchProcesses {

  private BenchProcesses() {}

  static String javaBinary() {
    return ProcessHandle.current().info().command().orElse("java");
  }

  static String classpath() {
    String override = System.getProperty("bench.classpath");
    if (override != null && !override.isBlank()) return override;
    for (String p :
        List.of("freeway-benchmark/target/benchmark.classpath", "target/benchmark.classpath")) {
      Path f = Path.of(p);
      if (Files.isRegularFile(f)) {
        try {
          String deps = Files.readString(f).trim();
          String classes = f.getParent().resolve("classes").toString();
          return classes + File.pathSeparator + deps;
        } catch (IOException ignored) {
        }
      }
    }
    // Fallback: derive the classpath from this class's own code source so
    // BenchFork also works when launched from an arbitrary working directory.
    try {
      var location =
          Path.of(BenchFork.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isDirectory(location)) {
        Path cpFile = location.getParent().resolve("benchmark.classpath");
        if (Files.isRegularFile(cpFile)) {
          String deps = Files.readString(cpFile).trim();
          return location + File.pathSeparator + deps;
        }
      }
    } catch (Exception ignored) {
    }
    return System.getProperty("java.class.path");
  }

  /** Waits until the child prints its {@code READY port=<n>} line, or fails with its log. */
  static int awaitReady(Process p, Path log, Duration timeout)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(log)) {
        int port = parseReadyPort(readAllSafe(log));
        if (port > 0) return port;
      }
      if (!p.isAlive()) throw new RuntimeException("Server died: " + readAllSafe(log));
      Thread.sleep(100);
    }
    throw new RuntimeException("Server not ready: " + readAllSafe(log));
  }

  /**
   * The port a child announced on its {@code READY port=<n> ...} line, or -1 while the line is not
   * there yet. Split out of {@link #awaitReady} so the handshake rule is testable on its own.
   */
  static int parseReadyPort(String logContent) {
    if (logContent == null) return -1;
    for (String line : logContent.lines().toList()) {
      if (!line.startsWith("READY ")) continue;
      for (String token : line.split("\\s+")) {
        if (token.startsWith("port=")) {
          try {
            return Integer.parseInt(token.substring("port=".length()));
          } catch (NumberFormatException e) {
            return -1;
          }
        }
      }
    }
    return -1;
  }

  static String readAllSafe(Path log) {
    try {
      return new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      return "(unreadable: " + e.getMessage() + ")";
    }
  }

  static void deleteSafe(Path file) {
    for (int i = 0; i < 5; i++) {
      try {
        Files.deleteIfExists(file);
        return;
      } catch (IOException e) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  static String prop(String key, String defaultValue) {
    String value = System.getProperty(key);
    return value != null && !value.isBlank() ? value : defaultValue;
  }

  static int intProp(String key, int defaultValue) {
    String value = System.getProperty(key);
    return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
  }
}
