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

/** IoC module that installs HikariCP as the primary connection pool. */
public final class HikariPoolModule implements ModuleEx {

  @Override
  public void bind(Binder binder) {
    binder
        .bind(Pool.class)
        .to(
            container -> {
              PoolConfig config = container.get(PoolConfig.class);
              return new HikariPool(config);
            })
        .id("hikari")
        .primary();
  }
}
