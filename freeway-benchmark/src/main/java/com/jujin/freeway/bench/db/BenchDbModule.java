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

import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.db.schema.SchemaEntity;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

/**
 * Configures SQLite database for benchmark result persistence.
 *
 * <p>Sets JDBC URL to {@code jdbc:sqlite:bench.db} (created in the working directory). Registers
 * the benchmark schema entities for auto-table-creation at startup.
 */
public final class BenchDbModule implements ModuleEx {

  @Override
  public void bind(Binder binder) {
    // Install DbModule with SQLite config
    binder.install(new DbModule());

    // Register schema entities for auto-creation
    binder.contribute(SchemaEntity.class).add(BenchSchema.all());
  }
}
