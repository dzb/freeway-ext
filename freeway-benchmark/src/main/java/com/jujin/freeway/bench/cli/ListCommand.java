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
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.db.Database;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code bench list} — shows recent benchmark runs from the SQLite database.
 *
 * <p>Arguments:
 *
 * <pre>
 * --limit=10    max rows to show (default: 10)
 * --engine=     filter by engine (optional)
 * </pre>
 */
public final class ListCommand implements Command {

  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  @Override
  public String name() {
    return "list";
  }

  @Override
  public void run(Context ctx) throws Exception {
    int limit = ctx.getInt("limit", 10);
    String engineFilter = ctx.get("engine", null);

    var container = ctx.container();
    var db = container.get(Database.class);

    var runs =
        new BenchRepository(db, container.get(Coercer.class)).recentRuns(engineFilter, limit);

    if (runs.isEmpty()) {
      System.out.println("No benchmark runs found.");
      return;
    }

    var rows = new ArrayList<BenchFormat.Row>();
    for (var r : runs) {
      rows.add(
          BenchFormat.Row.of(
              String.valueOf(r.id()),
              r.engine(),
              r.scenario(),
              String.valueOf(r.concurrency()),
              r.commitSha() == null || r.commitSha().isBlank() ? "—" : r.commitSha(),
              r.jdkInfo() != null && r.jdkInfo().length() > 20
                  ? r.jdkInfo().substring(0, 20)
                  : r.jdkInfo() == null ? "" : r.jdkInfo(),
              r.createdAt() != null
                  ? FMT.format(r.createdAt().atZone(ZoneId.systemDefault()))
                  : "—"));
    }
    System.out.println(
        BenchFormat.table(
            List.of("ID", "Engine", "Scenario", "Concur", "Commit", "JDK", "Created"),
            List.of(
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.LEFT,
                BenchFormat.Align.LEFT,
                BenchFormat.Align.RIGHT,
                BenchFormat.Align.LEFT,
                BenchFormat.Align.LEFT,
                BenchFormat.Align.LEFT),
            rows));
  }
}
