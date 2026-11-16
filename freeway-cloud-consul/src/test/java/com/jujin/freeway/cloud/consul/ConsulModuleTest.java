package com.jujin.freeway.cloud.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
  void placingTheModuleBindsTheConsulBackend() {
    try (Container container = Freeway.create(new ConsulModule())) {
      // The adapter is primary; its configured window identifies it.
      assertEquals(
          ConsulWiring.defaults().drainWindow(),
          container.get(ServiceRegistry.class).drainWindow());
      assertNotNull(container.get(ServiceDiscovery.class));
    }
  }
}
