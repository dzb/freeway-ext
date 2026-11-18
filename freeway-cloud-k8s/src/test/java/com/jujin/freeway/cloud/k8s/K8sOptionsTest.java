package com.jujin.freeway.cloud.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class K8sOptionsTest {

  @Test
  void normalizesCallerValues() {
    K8sOptions options = new K8sOptions(" default ", "", " HTTPS ", " api/ ", "", Duration.ZERO);

    assertEquals("default", options.namespace());
    assertEquals("https", options.scheme());
    assertEquals("/api", options.basePath());
    assertEquals(Duration.ZERO, options.resyncPeriod());
  }

  @Test
  void rejectsUnsupportedSchemeAndNegativeResync() {
    assertThrows(
        IllegalArgumentException.class, () -> new K8sOptions("", "", "ftp", "", "", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new K8sOptions("", "", "http", "", "", Duration.ofSeconds(-1)));
  }
}
