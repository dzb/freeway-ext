package com.jujin.freeway.fleet.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.observe.MetricsDefault;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FleetMaintenanceHookTest {

  @Test
  void evictionRemovesStaleLeasesAndCountsTheWork() throws InterruptedException {
    MemLeaseStore store = new MemLeaseStore();
    store.register(ServiceInstance.of("svc", "i1", ServiceEndpoint.of("http", "127.0.0.1", 8080)));
    MetricsDefault metrics = new MetricsDefault();
    FleetMaintenanceHook hook =
        new FleetMaintenanceHook(
            store, new FleetServerOptions(Duration.ofMillis(1), Duration.ofSeconds(1)), metrics);

    Thread.sleep(5);
    hook.evict();

    assertEquals(0, store.instanceCount());
    assertTrue(metrics.counterValue("fleet.server.eviction") > 0);
  }
}
