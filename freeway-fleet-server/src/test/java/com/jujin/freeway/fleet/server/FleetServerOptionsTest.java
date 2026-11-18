package com.jujin.freeway.fleet.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FleetServerOptionsTest {

  @Test
  void acceptsPositiveDurations() {
    FleetServerOptions options =
        new FleetServerOptions(Duration.ofSeconds(30), Duration.ofSeconds(10));
    assertEquals(Duration.ofSeconds(30), options.leaseTtl());
    assertEquals(Duration.ofSeconds(10), options.evictionInterval());
  }

  @Test
  void rejectsZeroAndNegativeDurations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetServerOptions(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetServerOptions(Duration.ofSeconds(1), Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetServerOptions(Duration.ofSeconds(-1), Duration.ofSeconds(1)));
  }
}
