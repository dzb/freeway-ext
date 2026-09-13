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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code HikariPoolModule} through the container: it must outrank the built-in {@code PoolDefault}
 * and read its leak-detection knob through the container's symbol cascade rather than through JVM
 * properties alone.
 */
class HikariPoolModuleContainerTest {

  @AfterEach
  void clearProperties() {
    System.clearProperty("freeway.db.url");
    System.clearProperty("freeway.db.username");
    System.clearProperty("freeway.db.password");
    System.clearProperty("freeway.db.pool.max-size");
    System.clearProperty("freeway.db.pool.min-idle");
    System.clearProperty("freeway.db.pool.leak-detection");
  }

  @Test
  void bindsPrimaryPoolByItsIdAndServesConnections() {
    System.setProperty("freeway.db.url", newDb());
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.max-size", "3");
    System.setProperty("freeway.db.pool.min-idle", "1");

    try (Container container = Freeway.create(new HikariPoolModule(), new DbModule())) {
      // DbModule binds PoolDefault plainly; this module binds its pool with id
      // "hikari" + primary, so the primary one wins without an ambiguity error
      // and the same binding is reachable by id.
      Pool primary = container.get(Pool.class);
      assertSame(
          container.get(Pool.class, "hikari"),
          primary,
          "the primary Pool binding must be this module's, not DbModule's plain one");

      Database db = container.get(Database.class);
      assertTrue(db.ping(), "the container-bound Hikari pool must serve connections");
      assertEquals(3, db.stats().maxSize(), "freeway.db.pool.max-size must reach HikariCP");
    }
  }

  @Test
  void leakDetectionResolvesThroughTheContainerCascade() {
    // PoolDefault ignores freeway.db.pool.leak-detection entirely, so a
    // malformed value failing by name proves the Hikari adapter is bound and
    // read the key from the container's SymbolSource.
    System.setProperty("freeway.db.url", newDb());
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.min-idle", "0");
    System.setProperty("freeway.db.pool.leak-detection", "soon");

    try (Container container = Freeway.create(new HikariPoolModule(), new DbModule())) {
      // Pool is a lazy binding: the provider runs on first use, so the
      // malformed value surfaces here, not at get().
      Pool pool = container.get(Pool.class);
      SqlException e = assertThrows(SqlException.class, pool::stats);
      assertTrue(
          e.getMessage().contains("freeway.db.pool.leak-detection"),
          "the failing key must be named, got: " + e.getMessage());
    } finally {
      System.clearProperty("freeway.db.pool.leak-detection");
    }
  }

  private static String newDb() {
    return "jdbc:h2:mem:hikari_module_"
        + UUID.randomUUID().toString().replace('-', '_')
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  }
}
