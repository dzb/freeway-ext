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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.dialect.H2Dialect;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class HikariPoolIntegrationTest {

  private static String newDb() {
    return "jdbc:h2:mem:"
        + UUID.randomUUID().toString().replace('-', '_')
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  }

  @Test
  void pingAndStatsReflectHikariPool() {
    PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
    HikariPool pool = new HikariPool(config);
    Database db = Database.create(Database.Wiring.defaults(config).withPool(pool));

    try (db) {
      assertTrue(db.ping(), "ping should succeed with HikariCP");

      DatabaseStats stats = db.stats();
      assertEquals(config.maxSize(), stats.maxSize(), "maxSize should match config from HikariCP");

      assertTrue(
          stats.idle() >= 1,
          "the connection ping borrowed must be back in the idle set, got idle=" + stats.idle());
      assertEquals(0, stats.active(), "no active connections after ping returns to pool");
    }
  }

  @Test
  void executeAndQueryWorkThroughHikariCP() {
    PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
    HikariPool pool = new HikariPool(config);
    Database db = Database.create(Database.Wiring.defaults(config).withPool(pool));

    try (db) {
      db.execute("create table items (id int primary key, name varchar(50))");
      db.execute("insert into items values (?, ?)", 1, "alpha");
      db.execute("insert into items values (?, ?)", 2, "beta");

      String name =
          db.query("select name from items where id = ?", 1).one(String.class).orElseThrow();
      assertEquals("alpha", name);

      long count = db.query("select count(*) from items").one(Long.class).orElseThrow();
      assertEquals(2L, count);

      DatabaseStats stats = db.stats();
      assertEquals(0, stats.active(), "no active connections after queries");
    }
  }

  @Test
  void customMaxSizeIsReflectedInStats() {
    PoolConfig config =
        new PoolConfig(
            newDb(),
            "sa",
            "",
            7,
            2,
            Duration.ofSeconds(30),
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30));

    HikariPool pool = new HikariPool(config);
    Database db = Database.create(Database.Wiring.defaults(config).withPool(pool));

    try (db) {
      DatabaseStats stats = db.stats();
      assertEquals(7, stats.maxSize(), "custom maxSize from HikariCP config");
    }
  }

  @Test
  void concurrentBorrowsStayWithinMaxSize() throws Exception {
    PoolConfig config =
        new PoolConfig(
            newDb(),
            "sa",
            "",
            3,
            0,
            Duration.ofSeconds(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30));

    HikariPool pool = new HikariPool(config);
    int threads = 5;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger success = new AtomicInteger(0);
    List<Throwable> failures = new ArrayList<>();

    for (int i = 0; i < threads; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  PooledConnection conn = pool.borrow();
                  success.incrementAndGet();
                  // hold the connection briefly, then always give it back
                  Thread.sleep(200);
                  pool.release(conn);
                } catch (Throwable t) {
                  synchronized (failures) {
                    failures.add(t);
                  }
                } finally {
                  done.countDown();
                }
              });
    }

    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertEquals(List.of(), failures, "every borrower must get a connection and release it");
    assertEquals(
        threads, success.get(), "borrows queue instead of failing while below the timeout");

    DatabaseStats stats = pool.stats();
    assertEquals(3, stats.maxSize());
    pool.close();
  }

  @Test
  void poolExhaustionThrowsWhenTimeoutExceeded() throws Exception {
    PoolConfig config =
        new PoolConfig(
            newDb(),
            "sa",
            "",
            1,
            0,
            Duration.ofMillis(500), // short timeout
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30));

    HikariPool pool = new HikariPool(config);
    PooledConnection first = pool.borrow();

    AtomicReference<Exception> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                pool.borrow();
              } catch (Exception ex) {
                failure.set(ex);
              } finally {
                done.countDown();
              }
            });

    assertTrue(done.await(10, TimeUnit.SECONDS));
    SqlException exhaustion = assertInstanceOf(SqlException.class, failure.get());
    assertNotNull(exhaustion.getCause(), "the HikariCP timeout must be the cause, not swallowed");

    pool.release(first);
    pool.close();
  }

  @Test
  void borrowAfterCloseThrows() {
    PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
    HikariPool pool = new HikariPool(config);

    pool.close();
    assertThrows(SqlException.class, pool::borrow, "borrow after close should throw");
  }

  @Test
  void releaseAfterCloseDoesNotThrow() {
    PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
    HikariPool pool = new HikariPool(config);

    PooledConnection conn = pool.borrow();
    pool.close();
    // releasing after close should not throw — HikariCP handles it
    assertDoesNotThrow(() -> pool.release(conn));
  }

  @Test
  void healthCheckQueryIsForwarded() throws Exception {
    PoolConfig config =
        new PoolConfig(
            newDb(),
            "sa",
            "",
            3,
            1,
            Duration.ofSeconds(5),
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            "select 1",
            Duration.ofSeconds(5),
            Duration.ofSeconds(30));

    HikariPool pool = new HikariPool(config);

    // Borrow several connections — HikariCP should validate them with the query
    List<PooledConnection> conns = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      conns.add(pool.borrow());
    }
    for (PooledConnection c : conns) {
      // verify each connection is alive
      try (Statement stmt = c.connection().createStatement();
          ResultSet rs = stmt.executeQuery("select 1")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
      pool.release(c);
    }

    pool.close();
  }

  @Test
  void statsActiveReflectsBorrowedConnections() {
    PoolConfig config =
        new PoolConfig(
            newDb(),
            "sa",
            "",
            5,
            0,
            Duration.ofSeconds(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(30),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30));

    HikariPool pool = new HikariPool(config);

    assertEquals(0, pool.stats().active());
    PooledConnection c1 = pool.borrow();
    assertEquals(1, pool.stats().active());
    assertTrue(pool.stats().borrowCount() >= 1, "borrowCount should track successful borrows");
    assertTrue(pool.stats().borrowWaitNanos() > 0, "borrowWaitNanos should be accumulated");
    PooledConnection c2 = pool.borrow();
    assertEquals(2, pool.stats().active());

    pool.release(c1);
    assertEquals(1, pool.stats().active());
    pool.release(c2);
    assertEquals(0, pool.stats().active());

    pool.close();
  }

  @Test
  void leakDetectionThresholdIsReadFromSystemProperty() {
    System.setProperty("freeway.db.pool.leak-detection", "30000");
    try {
      PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
      HikariPool pool = new HikariPool(config);
      assertEquals(30000L, pool.leakDetectionThreshold());
      pool.close();
    } finally {
      System.clearProperty("freeway.db.pool.leak-detection");
    }
  }

  @Test
  void closeIsIdempotent() {
    PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
    HikariPool pool = new HikariPool(config);

    pool.close();
    assertDoesNotThrow(pool::close, "second close should not throw");
  }

  @Test
  void releaseForeignConnectionFailsWithSqlException() {
    // Closing a foreign connection here would shut a physical connection
    // owned by another pool out from under it — fail loudly instead.
    // Mirrors PoolDefault's foreign-release guard.
    PoolConfig config = PoolConfig.defaults(newDb(), "sa", "");
    HikariPool pool = new HikariPool(config);
    try {
      PooledConnection foreign = () -> null;
      SqlException e = assertThrows(SqlException.class, () -> pool.release(foreign));
      assertTrue(
          e.getMessage().contains("Foreign PooledConnection"),
          "message must name the contract violation, got: " + e.getMessage());
      assertThrows(NullPointerException.class, () -> pool.release(null));
    } finally {
      pool.close();
    }
  }

  @Test
  void invalidateDestroysInsteadOfRecycling() throws Exception {
    // release(): HikariCP recycles — the connection stays in the pool. The
    // pool is sized 1/0 so the contrast is unambiguous.
    PoolConfig config = singleConnectionConfig(newDb());
    HikariPool pool = new HikariPool(config);
    try {
      PooledConnection recycled = pool.borrow();
      pool.release(recycled);
      assertEquals(1, pool.stats().idle(), "a released connection must be recycled");
      assertEquals(1, pool.stats().total());

      // invalidate(): HikariCP evicts — the entry leaves the pool and the
      // physical connection is closed, so nothing is handed out again.
      PooledConnection doomed = pool.borrow();
      pool.invalidate(doomed);
      assertEquals(0, awaitTotal(pool, 0), "an invalidated connection must leave the pool");
      assertEquals(0, pool.stats().idle());

      // ...and the pool still works: the next borrow dials a fresh connection.
      PooledConnection fresh = pool.borrow();
      assertTrue(fresh.connection().isValid(1));
      pool.release(fresh);
      assertEquals(1, pool.stats().idle());
    } finally {
      pool.close();
    }
  }

  @Test
  void repeatedInvalidateAndReleaseAfterInvalidateAreNoOps() {
    PoolConfig config = singleConnectionConfig(newDb());
    HikariPool pool = new HikariPool(config);
    try {
      PooledConnection conn = pool.borrow();
      pool.invalidate(conn);
      // Both are cleanup paths: neither may throw nor resurrect the entry.
      assertDoesNotThrow(() -> pool.invalidate(conn));
      assertDoesNotThrow(() -> pool.release(conn));
      assertEquals(0, awaitTotal(pool, 0));
    } finally {
      pool.close();
    }
  }

  @Test
  void invalidateRejectsForeignAndNullHandles() {
    PoolConfig config = singleConnectionConfig(newDb());
    HikariPool pool = new HikariPool(config);
    try {
      PooledConnection held = pool.borrow();
      assertThrows(NullPointerException.class, () -> pool.invalidate(null));

      PooledConnection foreign = () -> null;
      SqlException e = assertThrows(SqlException.class, () -> pool.invalidate(foreign));
      assertTrue(
          e.getMessage().contains("Foreign PooledConnection"),
          "message must name the contract violation, got: " + e.getMessage());
      assertEquals(1, pool.stats().active(), "a rejected handle must leave the pool untouched");
      pool.release(held);
    } finally {
      pool.close();
    }
  }

  @Test
  void invalidateAfterCloseIsNoOp() {
    PoolConfig config = singleConnectionConfig(newDb());
    HikariPool pool = new HikariPool(config);
    PooledConnection conn = pool.borrow();
    pool.close();
    assertDoesNotThrow(() -> pool.invalidate(conn), "cleanup after shutdown must not throw");
  }

  @Test
  void databaseInvalidatesConnectionWhoseStateCannotBeRestored() throws Exception {
    // The adapter-level payoff of Pool.invalidate: a connection whose
    // autoCommit cannot be restored must be destroyed, not recycled with
    // autoCommit still off. Under HikariCP the old "close the handle"
    // approach only rolled back and recycled it.
    AtomicBoolean failRestore = new AtomicBoolean();
    Driver driver = restoreFailingDriver("jdbc:freeway-hikari-restore:", failRestore);
    DriverManager.registerDriver(driver);
    try {
      PoolConfig config = singleConnectionConfig("jdbc:freeway-hikari-restore:tx");
      HikariPool pool = new HikariPool(config);
      Database db =
          Database.create(Database.Wiring.defaults(config).withPool(pool).withDialect(new H2Dialect()));
      try (db) {
        db.execute("create table t (id int)");

        // Healthy path: the connection is recycled, never invalidated.
        db.transaction(() -> db.execute("insert into t values (1)"));
        assertEquals(1, pool.stats().idle(), "a restored connection must be recycled");

        failRestore.set(true);
        db.transaction(() -> db.execute("insert into t values (2)"));

        assertEquals(
            0,
            awaitTotal(pool, 0),
            "an unrestorable connection must be destroyed, not left in the pool");

        // The pool is still usable: a fresh connection replaces the destroyed one.
        failRestore.set(false);
        assertTrue(db.ping());
      } finally {
        pool.close();
      }
    } finally {
      DriverManager.deregisterDriver(driver);
    }
  }

  private static PoolConfig singleConnectionConfig(String url) {
    return new PoolConfig(
        url,
        "sa",
        "",
        1,
        0,
        Duration.ofSeconds(5),
        Duration.ofMinutes(30),
        Duration.ofMinutes(10),
        Duration.ofSeconds(30),
        null,
        Duration.ofSeconds(5),
        Duration.ofSeconds(30));
  }

  /** HikariCP evicts asynchronously; poll until the pool reports {@code expected}. */
  private static int awaitTotal(HikariPool pool, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    int total = pool.stats().total();
    while (total != expected && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      total = pool.stats().total();
    }
    return total;
  }

  /**
   * Serves real H2 connections wrapped in a proxy that can fail {@code setAutoCommit(true)} on
   * demand — the failure that makes {@code Database} destroy instead of recycle.
   */
  private static Driver restoreFailingDriver(String urlPrefix, AtomicBoolean failRestore) {
    return new Driver() {
      @Override
      public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
          return null;
        }
        Connection delegate =
            new org.h2.Driver().connect(url.replace(urlPrefix, "jdbc:h2:mem:"), new Properties());
        return restoreFailingProxy(delegate, failRestore);
      }

      @Override
      public boolean acceptsURL(String url) {
        return url != null && url.startsWith(urlPrefix);
      }

      @Override
      public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
      }

      @Override
      public int getMajorVersion() {
        return 1;
      }

      @Override
      public int getMinorVersion() {
        return 0;
      }

      @Override
      public boolean jdbcCompliant() {
        return false;
      }

      @Override
      public Logger getParentLogger() {
        return Logger.getLogger("test");
      }
    };
  }

  private static Connection restoreFailingProxy(Connection delegate, AtomicBoolean failRestore) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if ("setAutoCommit".equals(method.getName())
              && Boolean.TRUE.equals(args[0])
              && failRestore.get()) {
            throw new SQLException("autoCommit restore exploded");
          }
          try {
            return method.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }
}
