package com.jujin.freeway.cloud.k8s;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.util.List;
import java.util.Objects;

/** Read-only ServiceDiscovery backed by the Kubernetes EndpointSlice informer cache. */
final class K8sDiscovery implements ServiceDiscovery, AutoCloseable {

  private final K8sOptions options;
  private final K8sClient client;

  K8sDiscovery(K8sOptions options) {
    this.options = Objects.requireNonNull(options, "options");
    this.client = new K8sClient(options);
  }

  @Override
  public List<ServiceInstance> instances(String serviceId) {
    return K8sEndpointSliceMapper.instances(client.slices(serviceId), serviceId, options);
  }

  @Override
  public void close() {
    client.close();
  }
}
