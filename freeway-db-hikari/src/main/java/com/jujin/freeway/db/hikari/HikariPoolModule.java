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

package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/** IoC module that installs HikariCP as the primary connection pool. */
public final class HikariPoolModule implements ModuleEx {

  @Override
  public void bind(Binder binder) {
    binder
        .bind(Pool.class)
        .to(
            container -> {
              PoolConfig config = container.get(PoolConfig.class);
              return new HikariPool(config, container.get(SymbolSource.class));
            })
        .id("hikari")
        .primary();

    // Declared vocabulary for the unknown-key check.
    binder.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
  }

  /**
   * This adapter's own config keys, spelled as full literals. The pool settings proper ({@code
   * freeway.db.*}) are core's {@code DbModule.ConfigKeys}; the key below is the one only this pool
   * resolves.
   */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    /** The namespace this table is fenced to — shared with the core {@code DbModule}. */
    public static final String PREFIX = "freeway.db";

    /**
     * Milliseconds a borrowed connection may be held before HikariCP logs a leak warning (unset
     * disables leak detection). HikariCP-only: core's {@code PoolDefault} has no counterpart for
     * it.
     */
    public static final String LEAK_DETECTION = "freeway.db.pool.leak-detection";
  }
}
