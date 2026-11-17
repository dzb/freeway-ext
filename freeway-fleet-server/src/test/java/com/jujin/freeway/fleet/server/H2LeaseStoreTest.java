package com.jujin.freeway.fleet.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class H2LeaseStoreTest {

  private H2LeaseStore store;
  private ServiceInstance instance;

  @BeforeEach
  void open() {
    String db = "jdbc:h2:mem:fleet-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    store = new H2LeaseStore(db, "sa", "", new JsonCodecDefault());
    instance = ServiceInstance.of("orders", "i1", ServiceEndpoint.of("http", "127.0.0.1", 8080));
  }

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void startingIsRegisteredButNotDiscoverableUntilReady() {
    store.register(instance, ServiceHealth.starting());
    assertEquals(1, store.instanceCount());
    assertTrue(store.liveReadyInstances("orders", Duration.ofSeconds(30)).isEmpty());

    assertTrue(store.markReady("orders", "i1", true));
    assertEquals(1, store.liveReadyInstances("orders", Duration.ofSeconds(30)).size());
  }

  @Test
  void renewUpdatesReadinessAndMissingIdsAreReported() {
    store.register(instance, ServiceHealth.starting());
    assertTrue(store.renew("orders", "i1", true));
    assertEquals(1, store.liveReadyInstances("orders", Duration.ofSeconds(30)).size());

    assertFalse(store.renew("orders", "ghost"));
    assertFalse(store.unregister("orders", "ghost"));
  }

  @Test
  void staleEntriesAreEvictedFromTheTable() throws InterruptedException {
    store.register(instance);
    // The store's clock has millisecond resolution; leave a boundary gap so
    // a zero max-age cannot race the registration timestamp.
    Thread.sleep(5);
    assertTrue(store.liveReadyInstances("orders", Duration.ZERO).isEmpty());
    assertEquals(0, store.instanceCount());
  }

  @Test
  void unregisterRemovesEvenAStartingInstance() {
    store.register(instance, ServiceHealth.starting());
    assertTrue(store.unregister("orders", "i1"));
    assertEquals(0, store.instanceCount());
  }
}
