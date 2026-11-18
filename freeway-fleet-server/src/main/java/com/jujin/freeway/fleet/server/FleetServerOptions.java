package com.jujin.freeway.fleet.server;

import java.time.Duration;
import java.util.Objects;

/** Runtime options owned by the Fleet Server control plane. */
record FleetServerOptions(Duration leaseTtl, Duration evictionInterval) {

  FleetServerOptions {
    leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
    evictionInterval = Objects.requireNonNull(evictionInterval, "evictionInterval");
    if (leaseTtl.isZero() || leaseTtl.isNegative()) {
      throw new IllegalArgumentException("fleet lease TTL must be positive: " + leaseTtl);
    }
    if (evictionInterval.isZero() || evictionInterval.isNegative()) {
      throw new IllegalArgumentException(
          "fleet eviction interval must be positive: " + evictionInterval);
    }
  }
}
