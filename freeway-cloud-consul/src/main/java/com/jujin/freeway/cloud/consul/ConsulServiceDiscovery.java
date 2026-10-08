package com.jujin.freeway.cloud.consul;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.util.List;
import java.util.Objects;

/**
 * Consul-backed {@link ServiceDiscovery}. Only passing instances are returned — {@code
 * ?passing=true} — which is the seam's own contract ("live and ready"), not a knob.
 */
final class ConsulServiceDiscovery implements ServiceDiscovery {

  private final ConsulClient client;

  public ConsulServiceDiscovery(ConsulClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public List<ServiceInstance> instances(String serviceId) {
    return client.instances(serviceId);
  }
}
