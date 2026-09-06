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

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HikariCP-backed {@link Pool} implementation. */
public final class HikariPool implements Pool {
  private static final Logger LOG = LoggerFactory.getLogger(HikariPool.class);

  private final HikariDataSource ds;
  private final HikariConfig hikariConfig;
  private final AtomicLong borrowCount = new AtomicLong();
  private final AtomicLong borrowWaitNanos = new AtomicLong();

  public HikariPool(PoolConfig config) {
    this(config, systemProperties());
  }

  /**
   * Container path: {@code SymbolSource} is a container builtin, so the leak-detection knob
   * resolves through the full cascade instead of JVM properties alone.
   */
  public HikariPool(PoolConfig config, SymbolSource symbols) {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(config.url());
    hc.setUsername(config.username());
    hc.setPassword(config.password());
    hc.setMaximumPoolSize(config.maxSize());
    hc.setMinimumIdle(config.minIdle());
    hc.setConnectionTimeout(config.connectionTimeout().toMillis());
    hc.setMaxLifetime(config.maxLifetime().toMillis());
    hc.setIdleTimeout(config.maxIdleTime().toMillis());
    if (config.healthCheckQuery() != null) {
      hc.setConnectionTestQuery(config.healthCheckQuery());
    }
    // healthCheckTimeout maps to HikariCP's validation timeout: the maximum
    // time a borrowed connection may take to pass its validity test.
    hc.setValidationTimeout(config.healthCheckTimeout().toMillis());
    String leakDetection = symbols.resolve("freeway.db.pool.leak-detection", null);
    if (leakDetection != null && !leakDetection.isBlank()) {
      try {
        hc.setLeakDetectionThreshold(Long.parseLong(leakDetection.trim()));
      } catch (NumberFormatException ex) {
        throw new SqlException(
            "freeway.db.pool.leak-detection must be a millisecond threshold, got: '"
                + leakDetection
                + "'",
            ex);
      }
    }
    // PoolConfig fields without a HikariCP equivalent are intentionally not
    // mapped: cleanInterval (Hikari runs its own housekeeping) and
    // queryTimeout (JDBC statement level, not pool level).
    this.hikariConfig = hc;
    try {
      this.ds = new HikariDataSource(hc);
    } catch (RuntimeException ex) {
      throw new SqlException("Failed to initialize HikariCP pool", ex);
    }
  }

  /**
   * Standalone path (tests, direct construction): system properties only, exactly the pre-cascade
   * behavior.
   */
  private static SymbolSource systemProperties() {
    return new SymbolSource() {
      @Override
      public String resolve(String name) {
        String value = System.getProperty(name);
        if (value == null) {
          throw new UnknownSymbolException(name);
        }
        return value;
      }

      @Override
      public String resolve(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
      }

      @Override
      public String expand(String input) {
        return input;
      }
    };
  }

  @Override
  public PooledConnection borrow() {
    long waitStart = System.nanoTime();
    try {
      Connection conn = ds.getConnection();
      borrowCount.incrementAndGet();
      borrowWaitNanos.addAndGet(System.nanoTime() - waitStart);
      return new HkConn(conn);
    } catch (SQLException e) {
      throw new SqlException("Failed to borrow connection", e);
    }
  }

  @Override
  public void release(PooledConnection conn) {
    Objects.requireNonNull(conn, "conn");
    if (!(conn instanceof HkConn hk)) {
      // Releasing a foreign connection here would close a physical
      // connection owned by another pool out from under it — fail loudly
      // instead. Mirrors PoolDefault's foreign-release guard.
      throw new SqlException(
          "Foreign PooledConnection rejected: "
              + conn.getClass().getName()
              + " does not belong to this HikariPool — release connections only to the pool that borrowed them");
    }
    try {
      hk.connection().close();
    } catch (SQLException ex) {
      if (ds.isClosed()) {
        // Releasing a connection after pool shutdown is an expected
        // path (HikariCP closes the underlying connection); don't warn.
        LOG.debug("Connection released after pool shutdown", ex);
      } else {
        LOG.warn("Failed to release connection back to HikariCP", ex);
      }
    }
  }

  @Override
  public DatabaseStats stats() {
    int active = 0;
    int idle = 0;
    int total = 0;
    int awaiting = 0;
    if (!ds.isClosed()) {
      var pool = ds.getHikariPoolMXBean();
      active = pool.getActiveConnections();
      idle = pool.getIdleConnections();
      total = pool.getTotalConnections();
      awaiting = pool.getThreadsAwaitingConnection();
    }
    return new DatabaseStats(
        active,
        idle,
        total,
        awaiting,
        hikariConfig.getMaximumPoolSize(),
        0, // longLeased — HikariCP does not expose per-connection borrow duration
        borrowCount.get(),
        borrowWaitNanos.get());
  }

  @Override
  public void close() {
    ds.close();
  }

  /** Package-private for tests: the configured leak-detection threshold in ms. */
  long leakDetectionThreshold() {
    return hikariConfig.getLeakDetectionThreshold();
  }

  private record HkConn(Connection connection) implements PooledConnection {}
}
