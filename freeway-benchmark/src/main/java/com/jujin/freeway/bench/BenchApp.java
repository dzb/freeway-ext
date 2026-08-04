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

package com.jujin.freeway.bench;

import com.jujin.freeway.bench.cli.CliModule;
import com.jujin.freeway.bench.db.BenchDbModule;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;

/**
 * {@code bench} — Freeway-powered CLI benchmark application.
 *
 * <p>Usage:
 *
 * <pre>
 * bench run --engine=freeway --concurrency=32 --requests=5000
 * bench list --limit=10
 * </pre>
 *
 * <p>Persistence: SQLite database ({@code bench.db}) created in the working directory.
 *
 * <p>This is a companion application that showcases Freeway's IoC, DbModule, EventBus, and Boot
 * capabilities while serving as a practical performance tool.
 */
public final class BenchApp {

  public static void main(String[] args) {
    // Configure SQLite before any module loads
    System.setProperty("freeway.db.url", "jdbc:sqlite:bench.db");
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.max-size", "1");
    System.setProperty("freeway.db.pool.min-idle", "0");

    // The benchmark CLI is a database-backed command line application, not a web
    // server. Auto-discovery is disabled because it would additionally load the
    // SPI modules on the classpath (e.g. DbModule from freeway-db), causing
    // duplicate contributions and "Multiple primary services" conflicts with
    // the transport engines bundled in this module.
    AppRuntime app =
        FreewayApp.of(new BenchDbModule(), new CliModule()).autoDiscovery(false).start();
    int exitCode = 0;
    try {
      if (!CliModule.dispatch(CliModule.container(), args)) {
        exitCode = 1;
      }
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
      exitCode = 1;
    } finally {
      app.close();
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }
}
