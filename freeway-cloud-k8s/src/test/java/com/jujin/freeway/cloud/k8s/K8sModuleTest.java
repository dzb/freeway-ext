package com.jujin.freeway.cloud.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.MissingBindingException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class K8sModuleTest {

  @Test
  void bindsOptionsAndDiscoveryButNeverARegistry() {
    try (Container container = Freeway.create(new K8sModule())) {
      K8sOptions options = container.get(K8sOptions.class);
      assertEquals("", options.namespace());
      assertEquals("", options.portName());
      assertEquals("http", options.scheme());
      assertEquals(Duration.ofSeconds(30), options.resyncPeriod());

      assertThrows(MissingBindingException.class, () -> container.get(ServiceRegistry.class));
    }
  }
}
