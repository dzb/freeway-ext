package com.jujin.freeway.db.hikari;

import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.SqlException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HikariCP-backed {@link Pool} implementation. */
public final class HikariPool implements Pool {
    private static final Logger LOG = LoggerFactory.getLogger(HikariPool.class);

    private final HikariDataSource ds;
    private final HikariConfig config;
    private final AtomicLong borrowCount = new AtomicLong(0);
    private final AtomicLong borrowWaitNanos = new AtomicLong(0);

    public HikariPool(PoolConfig config) {
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
        // PoolConfig fields without a HikariCP equivalent are intentionally not
        // mapped: cleanInterval (Hikari runs its own housekeeping),
        // healthCheckTimeout (covered by connectionTimeout + test query), and
        // queryTimeout (JDBC statement level, not pool level).
        this.config = hc;
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
        try {
            conn.connection().close();
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
        if (ds.isClosed()) {
            return new DatabaseStats(
                0, 0, 0, 0,
                config.getMaximumPoolSize(),
                0, // longLeased — HikariCP does not expose per-connection borrow duration
                borrowCount.get(),
                borrowWaitNanos.get()
            );
        }
        var pool = ds.getHikariPoolMXBean();
        return new DatabaseStats(
            pool.getActiveConnections(),
            pool.getIdleConnections(),
            pool.getTotalConnections(),
            pool.getThreadsAwaitingConnection(),
            config.getMaximumPoolSize(),
            0, // longLeased — HikariCP does not expose per-connection borrow duration
            borrowCount.get(),
            borrowWaitNanos.get()
        );
    }

    @Override
    public void close() {
        ds.close();
    }

    private record HkConn(Connection connection) implements PooledConnection {}
}
