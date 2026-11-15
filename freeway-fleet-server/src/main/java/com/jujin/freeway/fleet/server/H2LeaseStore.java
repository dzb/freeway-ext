package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.commons.json.JsonCodec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded H2 {@link LeaseStore} for a single Fleet Server process.
 *
 * <p>One connection guarded by the store's monitor: the server is a control
 * plane with a small write rate, and an embedded connection pool would add a
 * dependency without buying throughput. The schema is initialized idempotently
 * on construction; the URL is chosen by configuration so an operator can use a
 * file database (the default) or an in-memory database for tests.</p>
 *
 * <p>This is not shared HA storage. Two Fleet nodes must not point at one
 * embedded H2 file; that role belongs to a future PostgreSQL store.</p>
 */
public final class H2LeaseStore implements LeaseStore, AutoCloseable {

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS fleet_instance (
            service_id VARCHAR(255) NOT NULL,
            instance_id VARCHAR(255) NOT NULL,
            instance_json CLOB NOT NULL,
            live BOOLEAN NOT NULL,
            ready BOOLEAN NOT NULL,
            last_seen_ms BIGINT NOT NULL,
            PRIMARY KEY (service_id, instance_id)
        )
        """;

    private final Connection connection;
    private final JsonCodec codec;

    public H2LeaseStore(String url, String user, String password, JsonCodec codec) {
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        Connection opened = null;
        try {
            opened = DriverManager.getConnection(url, user, password);
            try (Statement statement = opened.createStatement()) {
                statement.execute(SCHEMA);
            }
        } catch (SQLException e) {
            closeQuietly(opened, e);
            throw new IllegalStateException("cannot open Fleet H2 store at " + url, e);
        }
        this.connection = opened;
    }

    private static void closeQuietly(Connection connection, SQLException failure) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public synchronized void register(ServiceInstance instance) {
        register(instance, ServiceHealth.up());
    }

    @Override
    public synchronized void register(ServiceInstance instance, ServiceHealth health) {
        String json = codec.toJson(instance);
        long now = Instant.now().toEpochMilli();
        try {
            int updated = update(
                "UPDATE fleet_instance SET instance_json = ?, live = ?, ready = ?, "
                    + "last_seen_ms = ? WHERE service_id = ? AND instance_id = ?",
                json, health.live(), health.ready(), now,
                instance.serviceId(), instance.instanceId());
            if (updated == 0) {
                update("INSERT INTO fleet_instance "
                        + "(service_id, instance_id, instance_json, live, ready, last_seen_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                    instance.serviceId(), instance.instanceId(), json,
                    health.live(), health.ready(), now);
            }
        } catch (SQLException e) {
            throw failure("register", e);
        }
    }

    @Override
    public synchronized boolean renew(String serviceId, String instanceId, boolean ready) {
        try {
            return update(
                "UPDATE fleet_instance SET live = TRUE, ready = ?, last_seen_ms = ? "
                    + "WHERE service_id = ? AND instance_id = ?",
                ready, Instant.now().toEpochMilli(), serviceId, instanceId) > 0;
        } catch (SQLException e) {
            throw failure("renew", e);
        }
    }

    @Override
    public synchronized boolean markReady(String serviceId, String instanceId, boolean ready) {
        try {
            return update(
                "UPDATE fleet_instance SET live = TRUE, ready = ?, last_seen_ms = ? "
                    + "WHERE service_id = ? AND instance_id = ?",
                ready, Instant.now().toEpochMilli(), serviceId, instanceId) > 0;
        } catch (SQLException e) {
            throw failure("markReady", e);
        }
    }

    @Override
    public synchronized boolean renew(String serviceId, String instanceId) {
        try {
            return update(
                "UPDATE fleet_instance SET last_seen_ms = ? "
                    + "WHERE service_id = ? AND instance_id = ?",
                Instant.now().toEpochMilli(), serviceId, instanceId) > 0;
        } catch (SQLException e) {
            throw failure("renew", e);
        }
    }

    @Override
    public synchronized void unregister(ServiceInstance instance) {
        unregister(instance.serviceId(), instance.instanceId());
    }

    @Override
    public synchronized boolean unregister(String serviceId, String instanceId) {
        try {
            return update("DELETE FROM fleet_instance WHERE service_id = ? AND instance_id = ?",
                serviceId, instanceId) > 0;
        } catch (SQLException e) {
            throw failure("unregister", e);
        }
    }

    @Override
    public synchronized int instanceCount() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM fleet_instance")) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw failure("instanceCount", e);
        }
    }

    @Override
    public synchronized List<ServiceInstance> liveReadyInstances(String serviceId, Duration maxAge) {
        long cutoff = Instant.now().minus(maxAge).toEpochMilli();
        try {
            update("DELETE FROM fleet_instance WHERE last_seen_ms < ?", cutoff);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT instance_json FROM fleet_instance "
                        + "WHERE service_id = ? AND live = TRUE AND ready = TRUE "
                        + "ORDER BY instance_id")) {
                statement.setString(1, serviceId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ServiceInstance> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(codec.fromJson(rows.getString(1), ServiceInstance.class));
                    }
                    return result;
                }
            }
        } catch (SQLException e) {
            throw failure("liveReadyInstances", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw failure("close", e);
        }
    }

    private int update(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static IllegalStateException failure(String operation, SQLException e) {
        return new IllegalStateException("Fleet H2 store " + operation + " failed", e);
    }
}
