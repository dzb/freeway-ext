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

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HikariCP-backed {@link Pool} implementation.
 *
 * <p>{@link #release(PooledConnection)} closes the HikariCP proxy, which rolls back an open
 * transaction, resets the connection state and recycles the connection — HikariCP's own definition
 * of a healthy return. {@link #invalidate(PooledConnection)} is the destroy path: it calls {@code
 * HikariDataSource.evictConnection}, which removes the entry from the pool and physically closes
 * the connection, so a connection whose state could not be restored never reaches another borrower.
 *
 * <p>Some {@link PoolConfig} knobs behave differently under HikariCP than under {@code
 * PoolDefault}:
 *
 * <ul>
 *   <li><b>Durations are normalized.</b> HikariCP logs a warning and rewrites values below its own
 *       floors: {@code maxLifetime} below 30s becomes 30min, {@code maxIdleTime} below 10s becomes
 *       10min (and is disabled when it is at or above {@code maxLifetime}, or when the pool is
 *       fixed-size), and a leak-detection threshold below 2s or above {@code maxLifetime} is
 *       disabled. {@code connectionTimeout} and {@code healthCheckTimeout} below 250ms are rejected
 *       by {@code HikariConfig} with an {@code IllegalArgumentException} naming the floor. {@code
 *       PoolDefault} honors every value exactly as configured.
 *   <li><b>{@code cleanInterval} is not mapped</b> — HikariCP runs its own housekeeping.
 * </ul>
 *
 * <p>{@link DatabaseStats#longLeased()} is always 0: HikariCP does not expose per-connection borrow
 * durations, so leak detection is configured through {@code freeway.db.pool.leak-detection}
 * (HikariCP's own threshold) instead of being reported here. {@link #close()} delegates to {@code
 * HikariDataSource.close()} and does not wait for borrowed connections to return; HikariCP aborts
 * them itself.
 */
public final class HikariPool implements Pool {
  private static final Logger LOG = LoggerFactory.getLogger(HikariPool.class);

  private final HikariDataSource ds;

  /**
   * Handles already destroyed by {@link #invalidate(PooledConnection)}. HikariCP's proxy must not
   * be closed after its pool entry was evicted (the entry's connection is gone and the reset path
   * throws), so {@link #release(PooledConnection)} has to recognize the handle and become a no-op.
   * Weak keys: an entry disappears once the caller drops the handle.
   */
  private final Set<PooledConnection> invalidated =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  private final AtomicLong borrowCount = new AtomicLong();
  private final AtomicLong borrowWaitNanos = new AtomicLong();

  public HikariPool(PoolConfig config) {
    // No container and no contributed CoerceRule: the standalone chain parses
    // with a plain coercer, and a plain -D lookup needs none at all.
    this(config, SymbolSource.of(new CoercerDefault(), SymbolProvider.systemProperties()));
  }

  /**
   * Container path: {@code SymbolSource} is a container builtin, so the leak-detection knob
   * resolves through the full cascade instead of JVM properties alone.
   */
  public HikariPool(PoolConfig config, SymbolSource symbols) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(symbols, "symbols");
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
    try {
      this.ds = new HikariDataSource(hc);
    } catch (RuntimeException ex) {
      throw new SqlException("Failed to initialize HikariCP pool", ex);
    }
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
    if (invalidated.contains(hk)) {
      // Already destroyed by invalidate(): HikariCP's evicted pool entry has
      // no connection left, so closing the proxy would throw from its reset
      // path. Cleanup that invalidates and then releases must be a no-op.
      return;
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
  public void invalidate(PooledConnection conn) {
    Objects.requireNonNull(conn, "conn");
    if (!(conn instanceof HkConn hk)) {
      throw new SqlException(
          "Foreign PooledConnection rejected: "
              + conn.getClass().getName()
              + " does not belong to this HikariPool — invalidate connections only to the pool that borrowed them");
    }
    // Mark before evicting: a cleanup path that releases afterwards must see
    // the handle as destroyed.
    invalidated.add(hk);
    if (ds.isClosed()) {
      // Pool already shut down: HikariCP closed the physical connection.
      return;
    }
    // evictConnection destroys the physical connection; the proxy's close()
    // (release) would only roll back and recycle it.
    ds.evictConnection(hk.connection());
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
        ds.getMaximumPoolSize(),
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
    return ds.getLeakDetectionThreshold();
  }

  private record HkConn(Connection connection) implements PooledConnection {}
}
