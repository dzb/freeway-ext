package com.jujin.freeway.cloud.k8s;

import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns the Kubernetes client and the namespace-wide EndpointSlice informer. */
final class K8sClient implements AutoCloseable {

  private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
  private static final String SERVICE_NAME_INDEX = "service-name";

  private final KubernetesClient client;
  private final SharedIndexInformer<EndpointSlice> informer;

  K8sClient(K8sOptions options) {
    this(build(Objects.requireNonNull(options, "options")), options);
  }

  /** Test/embedding seam: use a caller-owned client while owning the informer lifecycle. */
  K8sClient(KubernetesClient client, K8sOptions options) {
    this.client = Objects.requireNonNull(client, "client");
    Objects.requireNonNull(options, "options");
    SharedIndexInformer<EndpointSlice> started = null;
    try {
      String selectedNamespace =
          options.namespace().isBlank() ? client.getNamespace() : options.namespace();
      if (selectedNamespace == null || selectedNamespace.isBlank()) {
        throw new IllegalStateException(
            "Kubernetes namespace is unavailable; set " + K8sModule.ConfigKeys.NAMESPACE);
      }
      started =
          client
              .discovery()
              .v1()
              .endpointSlices()
              .inNamespace(selectedNamespace)
              .runnableInformer(options.resyncPeriod().toMillis());
      started.addIndexers(
          Map.of(
              SERVICE_NAME_INDEX,
              slice -> {
                String serviceName = K8sEndpointSliceMapper.serviceName(slice);
                return serviceName == null ? List.of() : List.of(serviceName);
              }));
      started.start().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!started.hasSynced()) {
        throw new IllegalStateException(
            "Kubernetes EndpointSlice informer did not report an initial sync");
      }
      informer = started;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closeQuietly(started, this.client);
      throw new IllegalStateException("Interrupted while starting Kubernetes discovery", e);
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      closeQuietly(started, this.client);
      throw new IllegalStateException("Unable to start Kubernetes EndpointSlice discovery", e);
    }
  }

  private static KubernetesClient build(K8sOptions options) {
    KubernetesClientBuilder builder = new KubernetesClientBuilder();
    if (!options.kubeconfig().isBlank()) {
      builder.withConfig(Config.fromKubeconfig(new File(options.kubeconfig())));
    }
    return builder.build();
  }

  List<EndpointSlice> slices(String serviceId) {
    if (serviceId == null || serviceId.isBlank()) {
      return List.of();
    }
    return List.copyOf(informer.getIndexer().byIndex(SERVICE_NAME_INDEX, serviceId));
  }

  private static void closeQuietly(
      SharedIndexInformer<EndpointSlice> informer, KubernetesClient client) {
    try {
      if (informer != null) {
        informer.close();
      }
    } catch (RuntimeException ignored) {
      // Preserve the startup failure that triggered this cleanup.
    }
    try {
      client.close();
    } catch (RuntimeException ignored) {
      // Preserve the startup failure that triggered this cleanup.
    }
  }

  @Override
  public void close() {
    try {
      informer.close();
    } finally {
      client.close();
    }
  }
}
