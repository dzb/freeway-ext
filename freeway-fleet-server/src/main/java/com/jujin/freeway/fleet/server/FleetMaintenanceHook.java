package com.jujin.freeway.fleet.server;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic lease cleanup for the Fleet Server. */
final class FleetMaintenanceHook implements RuntimeHook {

  private static final Logger LOG = LoggerFactory.getLogger(FleetMaintenanceHook.class);

  private final LeaseStore store;
  private final FleetServerOptions options;
  private final Metrics metrics;
  private volatile ScheduledExecutorService scheduler;

  FleetMaintenanceHook(LeaseStore store, FleetServerOptions options, Metrics metrics) {
    this.store = Objects.requireNonNull(store, "store");
    this.options = Objects.requireNonNull(options, "options");
    this.metrics = metrics == null ? NoopMetrics.INSTANCE : metrics;
  }

  @Override
  public void start(Container container) {
    long interval = options.evictionInterval().toMillis();
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("fleet-store-eviction").factory());
    scheduler.scheduleWithFixedDelay(this::evict, interval, interval, TimeUnit.MILLISECONDS);
  }

  void evict() {
    try {
      int removed = store.evictExpired(options.leaseTtl());
      if (removed > 0) {
        metrics.counter("fleet.server.eviction").add(removed);
      }
    } catch (RuntimeException failed) {
      metrics.counter("fleet.server.eviction.failures").increment();
      LOG.warn("Fleet lease eviction failed: {}", failed.getMessage());
    }
  }

  @Override
  public void stop(Container container) {
    ScheduledExecutorService current = scheduler;
    if (current != null) {
      current.shutdownNow();
      scheduler = null;
    }
  }
}
