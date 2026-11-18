package com.jujin.freeway.cloud.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.ServiceInstance;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class K8sEndpointSliceMapperTest {

  private static final K8sOptions HTTP =
      new K8sOptions("", "http", "http", "", "", Duration.ofSeconds(30));

  @Test
  void mapsReadyEndpointSliceIntoCandidateInstance() {
    EndpointSlice slice =
        new EndpointSliceBuilder()
            .withNewMetadata()
            .withName("orders-a")
            .addToLabels(K8sEndpointSliceMapper.SERVICE_NAME_LABEL, "orders")
            .endMetadata()
            .addNewEndpoint()
            .withAddresses("10.0.0.1")
            .withNewConditions()
            .withReady(true)
            .endConditions()
            .withNewTargetRef()
            .withUid("pod-uid-1")
            .withNamespace("default")
            .withName("orders-a")
            .endTargetRef()
            .withZone("zone-a")
            .withNodeName("node-a")
            .withHostname("orders-a")
            .endEndpoint()
            .addNewPort()
            .withName("http")
            .withPort(8080)
            .withProtocol("TCP")
            .endPort()
            .build();

    List<ServiceInstance> instances =
        K8sEndpointSliceMapper.instances(List.of(slice), "orders", HTTP);

    assertEquals(1, instances.size());
    ServiceInstance instance = instances.getFirst();
    assertEquals("orders", instance.serviceId());
    assertEquals("pod-uid-1", instance.instanceId());
    assertEquals("10.0.0.1", instance.endpoint().host());
    assertEquals(8080, instance.endpoint().port());
    assertEquals("http", instance.endpoint().scheme());
    assertEquals("zone-a", instance.metadata().get("zone"));
    assertEquals("node-a", instance.metadata().get("node"));
    assertEquals("orders-a", instance.metadata().get("hostname"));
  }

  @Test
  void ignoresNotReadyEndpointsAndOtherServices() {
    EndpointSlice notReady =
        slice("orders-a", "orders", false, "10.0.0.1", "pod-1", List.of(port("http", 8080)));
    EndpointSlice other =
        slice("payments-a", "payments", true, "10.0.0.2", "pod-2", List.of(port("http", 8080)));

    assertTrue(
        K8sEndpointSliceMapper.instances(List.of(notReady, other), "orders", HTTP).isEmpty());
  }

  @Test
  void multiplePortsRequireAnExplicitPortName() {
    EndpointSlice slice =
        slice(
            "orders-a",
            "orders",
            true,
            "10.0.0.1",
            "pod-1",
            List.of(port("http", 8080), port("admin", 9090)));

    K8sOptions singlePort = new K8sOptions("", "", "http", "", "", Duration.ofSeconds(30));
    assertTrue(K8sEndpointSliceMapper.instances(List.of(slice), "orders", singlePort).isEmpty());

    K8sOptions admin = new K8sOptions("", "admin", "http", "", "", Duration.ofSeconds(30));
    List<ServiceInstance> instances =
        K8sEndpointSliceMapper.instances(List.of(slice), "orders", admin);
    assertEquals(1, instances.size());
    assertEquals(9090, instances.getFirst().endpoint().port());
  }

  @Test
  void ignoresTerminatingEndpoints() {
    EndpointSlice slice =
        new EndpointSliceBuilder()
            .withNewMetadata()
            .withName("orders-a")
            .addToLabels(K8sEndpointSliceMapper.SERVICE_NAME_LABEL, "orders")
            .endMetadata()
            .addNewEndpoint()
            .withAddresses("10.0.0.1")
            .withNewConditions()
            .withReady(true)
            .withTerminating(true)
            .endConditions()
            .endEndpoint()
            .addNewPort()
            .withName("http")
            .withPort(8080)
            .withProtocol("TCP")
            .endPort()
            .build();

    assertTrue(K8sEndpointSliceMapper.instances(List.of(slice), "orders", HTTP).isEmpty());
  }

  @Test
  void ignoresNonTcpPorts() {
    EndpointSlice slice =
        slice(
            "orders-a",
            "orders",
            true,
            "10.0.0.1",
            "pod-1",
            List.of(
                new io.fabric8.kubernetes.api.model.discovery.v1.EndpointPortBuilder()
                    .withName("http")
                    .withPort(8080)
                    .withProtocol("UDP")
                    .build()));

    assertTrue(K8sEndpointSliceMapper.instances(List.of(slice), "orders", HTTP).isEmpty());
  }

  @Test
  void fallsBackToNamespaceAndNameWhenUidIsMissing() {
    EndpointSlice slice =
        slice("orders-a", "orders", true, "10.0.0.1", "", List.of(port("http", 8080)));

    List<ServiceInstance> instances =
        K8sEndpointSliceMapper.instances(List.of(slice), "orders", HTTP);
    assertEquals(1, instances.size());
    assertEquals("default/orders-a", instances.getFirst().instanceId());
  }

  private static EndpointSlice slice(
      String name,
      String service,
      boolean ready,
      String address,
      String uid,
      List<io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort> ports) {
    return new EndpointSliceBuilder()
        .withNewMetadata()
        .withName(name)
        .addToLabels(K8sEndpointSliceMapper.SERVICE_NAME_LABEL, service)
        .endMetadata()
        .addNewEndpoint()
        .withAddresses(address)
        .withNewConditions()
        .withReady(ready)
        .endConditions()
        .withNewTargetRef()
        .withUid(uid)
        .withNamespace("default")
        .withName(name)
        .endTargetRef()
        .endEndpoint()
        .withPorts(ports)
        .build();
  }

  private static io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort port(
      String name, int number) {
    return new io.fabric8.kubernetes.api.model.discovery.v1.EndpointPortBuilder()
        .withName(name)
        .withPort(number)
        .withProtocol("TCP")
        .build();
  }
}
