package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.http.HealthResult;
import java.util.Objects;

/** Readiness contribution for the Fleet server's lease store. */
final class FleetServerHealth implements CloudHealthContributor {

  private final LeaseStore store;

  FleetServerHealth(LeaseStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public String name() {
    return "fleet-server";
  }

  @Override
  public HealthResult check() {
    try {
      store.ping();
      return new HealthResult(true, "store ok");
    } catch (RuntimeException failed) {
      return HealthResult.unhealthy("fleet store unavailable: " + failed.getMessage());
    }
  }
}
