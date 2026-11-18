package com.jujin.freeway.cloud.k8s;

import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure EndpointSlice-to-ServiceInstance mapping, kept separate from the informer lifecycle. */
final class K8sEndpointSliceMapper {

  static final String SERVICE_NAME_LABEL = "kubernetes.io/service-name";
  private static final Logger LOG = LoggerFactory.getLogger(K8sEndpointSliceMapper.class);

  private K8sEndpointSliceMapper() {}

  static List<ServiceInstance> instances(
      Iterable<EndpointSlice> slices, String serviceId, K8sOptions options) {
    if (serviceId == null || serviceId.isBlank()) {
      return List.of();
    }
    Map<String, ServiceInstance> byInstance = new LinkedHashMap<>();
    for (EndpointSlice slice : slices) {
      if (!serviceId.equals(serviceName(slice))) {
        continue;
      }
      EndpointPort port = selectPort(slice, options.portName());
      if (port == null || port.getPort() == null) {
        continue;
      }
      for (Endpoint endpoint : endpoints(slice)) {
        if (!ready(endpoint)) {
          continue;
        }
        String address = firstAddress(endpoint);
        if (address == null) {
          continue;
        }
        String instanceId = instanceId(endpoint, address, port.getPort());
        try {
          ServiceInstance instance =
              ServiceInstance.of(
                  serviceId,
                  instanceId,
                  new ServiceEndpoint(
                      options.scheme(), address, port.getPort(), options.basePath()),
                  metadata(endpoint));
          byInstance.putIfAbsent(instanceId, instance);
        } catch (IllegalArgumentException invalidEndpoint) {
          LOG.warn(
              "Skipping invalid Kubernetes endpoint for service '{}': {}",
              serviceId,
              invalidEndpoint.getMessage());
        }
      }
    }
    return List.copyOf(byInstance.values());
  }

  static String serviceName(EndpointSlice slice) {
    if (slice.getMetadata() == null || slice.getMetadata().getLabels() == null) {
      return null;
    }
    return slice.getMetadata().getLabels().get(SERVICE_NAME_LABEL);
  }

  private static List<Endpoint> endpoints(EndpointSlice slice) {
    return slice.getEndpoints() == null ? List.of() : slice.getEndpoints();
  }

  private static EndpointPort selectPort(EndpointSlice slice, String portName) {
    List<EndpointPort> ports = new ArrayList<>();
    if (slice.getPorts() != null) {
      for (EndpointPort port : slice.getPorts()) {
        if (port.getPort() == null || !tcp(port)) {
          continue;
        }
        ports.add(port);
      }
    }
    if (ports.isEmpty()) {
      return null;
    }
    if (!portName.isBlank()) {
      for (EndpointPort port : ports) {
        if (portName.equals(port.getName())) {
          return port;
        }
      }
      return null;
    }
    if (ports.size() == 1) {
      return ports.get(0);
    }
    LOG.warn(
        "EndpointSlice for service '{}' has multiple TCP ports; set {} to select one",
        serviceName(slice),
        K8sModule.ConfigKeys.PORT_NAME);
    return null;
  }

  private static boolean tcp(EndpointPort port) {
    return port.getProtocol() == null || "TCP".equalsIgnoreCase(port.getProtocol());
  }

  private static boolean ready(Endpoint endpoint) {
    return endpoint.getConditions() != null
        && Boolean.TRUE.equals(endpoint.getConditions().getReady())
        && !Boolean.TRUE.equals(endpoint.getConditions().getTerminating());
  }

  private static String firstAddress(Endpoint endpoint) {
    if (endpoint.getAddresses() == null) {
      return null;
    }
    for (String address : endpoint.getAddresses()) {
      if (address != null && !address.isBlank()) {
        return address;
      }
    }
    return null;
  }

  private static String instanceId(Endpoint endpoint, String address, int port) {
    ObjectReference target = endpoint.getTargetRef();
    if (target != null) {
      if (target.getUid() != null && !target.getUid().isBlank()) {
        return target.getUid();
      }
      if (target.getName() != null && !target.getName().isBlank()) {
        return target.getNamespace() == null || target.getNamespace().isBlank()
            ? target.getName()
            : target.getNamespace() + "/" + target.getName();
      }
    }
    return address + ":" + port;
  }

  private static Map<String, String> metadata(Endpoint endpoint) {
    Map<String, String> metadata = new LinkedHashMap<>();
    String zone = endpoint.getZone();
    if (zone == null && endpoint.getDeprecatedTopology() != null) {
      zone = endpoint.getDeprecatedTopology().get("topology.kubernetes.io/zone");
    }
    if (zone != null && !zone.isBlank()) {
      metadata.put("zone", zone);
    }
    if (endpoint.getNodeName() != null && !endpoint.getNodeName().isBlank()) {
      metadata.put("node", endpoint.getNodeName());
    }
    if (endpoint.getHostname() != null && !endpoint.getHostname().isBlank()) {
      metadata.put("hostname", endpoint.getHostname());
    }
    return metadata;
  }
}
