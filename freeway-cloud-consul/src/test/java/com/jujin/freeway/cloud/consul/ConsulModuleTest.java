package com.jujin.freeway.cloud.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.jujin.freeway.cloud.annotation.Local;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Placing the module replaces the core's {@code @Local} discovery defaults — the contract the
 * adapter exists to satisfy, asserted on the container rather than only on the classes.
 *
 * <p>An interface binding is proxied by the container, so the concrete adapter is not what {@code
 * get} hands back; the assertion is on behavior instead. {@code drainWindow()} is the
 * discriminator: the Consul adapter answers its configured window, while the in-process default
 * answers {@code ZERO} (an endpoint disappears the moment it is unregistered).
 *
 * <p>The vocabulary contribution is exercised by construction: {@code KnownKeys.of} fails the bind
 * when a {@code ConfigKeys} entry sits outside the declared prefix, so a clean {@code create} is
 * that check passing.
 */
class ConsulModuleTest {

  @Test
  void placingTheModuleReplacesTheLocalDefaults() {
    try (Container container = Freeway.create(new CloudDiscoveryModule(), new ConsulModule())) {
      assertFalse(
          container.isActiveBinding(ServiceRegistry.class, Local.class),
          "the @Local in-process registry must no longer be the active binding");
      assertFalse(
          container.isActiveBinding(ServiceDiscovery.class, Local.class),
          "the @Local in-process discovery must no longer be the active binding");

      // The active registry answers the Consul window, not the local ZERO.
      assertEquals(
          ConsulWiring.defaults().drainWindow(),
          container.get(ServiceRegistry.class).drainWindow(),
          "the active registry must be the Consul adapter, not the in-process default");
    }
  }

  @Test
  void theLocalDefaultIsStillReachableUnderItsMarker() {
    try (Container container = Freeway.create(new CloudDiscoveryModule(), new ConsulModule())) {
      // The marker stays meaningful: a caller may still ask for the
      // built-in explicitly even while the adapter is primary — and it is
      // the built-in by behavior (zero drain), not the adapter.
      assertEquals(
          Duration.ZERO,
          container.get(ServiceRegistry.class, Local.class).drainWindow(),
          "the @Local binding must still resolve to the in-process registry");
    }
  }
}
