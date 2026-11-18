package com.jujin.freeway.cloud.k8s;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Caller-selected values for the Kubernetes discovery adapter.
 *
 * @param namespace namespace to watch; blank uses the client's in-cluster/kubeconfig namespace
 * @param portName EndpointSlice port name; blank accepts one TCP port and rejects ambiguity
 * @param scheme endpoint scheme published on every instance, {@code http} or {@code https}
 * @param basePath optional base path published on every instance
 * @param kubeconfig optional kubeconfig path for local development; blank uses SDK auto-config
 * @param resyncPeriod informer resync period; zero disables resync
 */
public record K8sOptions(
    String namespace,
    String portName,
    String scheme,
    String basePath,
    String kubeconfig,
    Duration resyncPeriod) {

  public K8sOptions {
    namespace = blankToEmpty(namespace);
    portName = blankToEmpty(portName);
    scheme = blankToEmpty(scheme).toLowerCase(Locale.ROOT);
    if (scheme.isEmpty()) {
      throw new IllegalArgumentException("scheme must not be blank");
    }
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("scheme must be http or https: " + scheme);
    }
    basePath = normalizeBasePath(basePath);
    kubeconfig = blankToEmpty(kubeconfig);
    Objects.requireNonNull(resyncPeriod, "resyncPeriod");
    if (resyncPeriod.isNegative()) {
      throw new IllegalArgumentException("resyncPeriod must not be negative");
    }
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.strip();
  }

  private static String normalizeBasePath(String value) {
    String path = blankToEmpty(value);
    if (path.isEmpty()) {
      return "";
    }
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }
}
