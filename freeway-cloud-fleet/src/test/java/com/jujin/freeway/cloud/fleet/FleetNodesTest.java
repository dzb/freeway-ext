package com.jujin.freeway.cloud.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FleetNodesTest {

  @Test
  void acceptsHttpAndHttpsNodes() {
    FleetNodes nodes =
        new FleetNodes(
            List.of(URI.create("http://127.0.0.1:8060"), URI.create("https://fleet.example:8443")),
            "",
            Duration.ofSeconds(1));

    assertEquals(2, nodes.addresses().size());
  }

  @Test
  void rejectsNonHttpSchemesAndAuthorityNoise() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetNodes(List.of(URI.create("ftp://fleet:21")), "", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetNodes(List.of(URI.create("http://user@fleet:8060")), "", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetNodes(List.of(URI.create("http://fleet:8060/path")), "", Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FleetNodes(List.of(URI.create("http://fleet:8060?x=1")), "", Duration.ZERO));
  }

  @Test
  void blankOrEmptyNodeListsFailWithTheKeyName() {
    assertThrows(
        IllegalArgumentException.class, () -> new FleetNodes(List.of(), "", Duration.ZERO));
  }

  @Test
  void minSuccessesMustFitTheNodeCount() {
    List<URI> two = List.of(URI.create("http://a:1"), URI.create("http://b:2"));
    assertEquals(2, new FleetNodes(two, "", Duration.ZERO, 2).minSuccesses());
    assertThrows(IllegalArgumentException.class, () -> new FleetNodes(two, "", Duration.ZERO, 3));
    assertThrows(IllegalArgumentException.class, () -> new FleetNodes(two, "", Duration.ZERO, 0));
  }
}
