package com.jujin.freeway.fleet.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FleetServerHealthTest {

  @Test
  void anAvailableMemoryStoreIsHealthy() {
    FleetServerHealth health = new FleetServerHealth(new MemLeaseStore());
    assertTrue(health.check().healthy());
  }

  @Test
  void aClosedH2StoreIsUnhealthy() {
    H2LeaseStore store =
        new H2LeaseStore(
            "jdbc:h2:mem:fleet-health-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
            "sa",
            "",
            new JsonCodecDefault());
    store.close();

    assertFalse(new FleetServerHealth(store).check().healthy());
  }
}
