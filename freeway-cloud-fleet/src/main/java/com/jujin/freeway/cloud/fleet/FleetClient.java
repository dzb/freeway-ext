package com.jujin.freeway.cloud.fleet;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.http.HttpClients;
import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.fleet.FleetCodec;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.MediaTypes;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wire client for a fleet of registry nodes: writes fan out concurrently to every node, reads
 * union the answers, and any one node staying alive keeps the fleet working.
 *
 * <p>The wire format is the registry node's default codec, not the application's: an application
 * that customized its own {@code JsonCodec} must not silently change what the nodes are asked to
 * parse. That is why the codec here is owned, not injected.
 *
 * <p>Renew doubles as per-node self-healing: a node that answers 404 (it restarted, its table is
 * empty) is re-registered on the spot, so a restarted node is repopulated by the very next
 * heartbeat of every instance instead of waiting for a full failure cycle.
 *
 * <p>This class owns transport only. Membership scheduling, readiness gating, drain and member
 * state belong to {@code freeway-cloud}'s {@code MemberHook}; this adapter must not duplicate them.
 */
final class FleetClient implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(FleetClient.class);

  private final FleetNodes nodes;
  private final HttpClient http;
  private final JsonCodec codec = FleetCodec.JSON;
  private final Metrics metrics;
  private final ExecutorService fanout;

  /**
   * Readiness this client last published; carried on every heartbeat so a restarted node learns it
   * without a second call.
   */
  private volatile boolean ready = true;

  FleetClient(FleetNodes nodes) {
    this(nodes, NoopMetrics.INSTANCE);
  }

  FleetClient(FleetNodes nodes, Metrics metrics) {
    this.nodes = Objects.requireNonNull(nodes, "nodes");
    this.metrics = metrics == null ? NoopMetrics.INSTANCE : metrics;
    this.http = HttpClients.builder(nodes.timeout(), null).build();
    this.fanout = Executors.newVirtualThreadPerTaskExecutor();
  }

  /** Writes the instance to every node; every node failing fails loudly. */
  public void register(ServiceInstance instance) {
    register(instance, ServiceHealth.up());
  }

  /**
   * Writes the instance with the probe state it should be registered under: a warming instance goes
   * in as {@link ServiceHealth#starting()}, so it is in the registry but not in discovery from the
   * first write. The readiness bit also rides the heartbeat from then on.
   */
  public void register(ServiceInstance instance, ServiceHealth health) {
    Objects.requireNonNull(instance, "instance");
    Objects.requireNonNull(health, "health");
    this.ready = health.ready();
    List<NodeResult> results =
        fanOutOrThrow(
            "register",
            node ->
                exchange(
                    node,
                    "POST",
                    FleetProtocol.register(instance.serviceId()) + "?ready=" + health.ready(),
                    codec.toJson(instance)));
    int successes = 0;
    for (NodeResult result : results) {
      if (successful(result)) {
        successes++;
      } else {
        logFailure("register", result);
      }
    }
    if (successes < nodes.minSuccesses()) {
      throw new IllegalStateException(
          (successes == 0
                  ? "no registry node accepted"
                  : "only " + successes + " of " + results.size() + " registry nodes accepted")
              + " the register request for "
              + instance.instanceId()
              + "; required "
              + nodes.minSuccesses());
    }
    if (successes < results.size()) {
      count("fleet.client.fanout.partial", Map.of("operation", "register"));
    }
  }

  /**
   * Publishes readiness. Best-effort and immediate: the explicit route makes a warming instance
   * discoverable the moment it is ready; a node that predates the route answers 404 and the bit
   * still rides the next heartbeat, so the cost of an older node is a delay, not a divergence.
   */
  public void markReady(ServiceInstance instance, boolean ready) {
    Objects.requireNonNull(instance, "instance");
    this.ready = ready;
    List<NodeResult> results =
        fanOutOrThrow(
            "ready",
            node ->
                exchange(
                    node,
                    "POST",
                    FleetProtocol.ready(instance.serviceId(), instance.instanceId()),
                    "{\"ready\":" + ready + "}"));
    for (NodeResult result : results) {
      if (!successful(result)) {
        LOG.debug(
            "Registry node {} answered {} to a readiness publish; the next heartbeat will carry it",
            result.node(),
            status(result));
      }
    }
  }

  public boolean renew(String serviceId, String instanceId) {
    Objects.requireNonNull(serviceId, "serviceId");
    Objects.requireNonNull(instanceId, "instanceId");
    List<NodeResult> results =
        fanOutOrThrow(
            "renew",
            node ->
                exchange(
                    node,
                    "POST",
                    FleetProtocol.renew(serviceId, instanceId),
                    "{\"ready\":" + ready + "}"));
    boolean aliveSomewhere = false;
    boolean lostSomewhere = false;
    for (NodeResult result : results) {
      if (result.failure() != null) {
        LOG.warn(
            "Registry node {} unreachable for a heartbeat: {}",
            result.node(),
            result.failure().getMessage());
        continue;
      }
      int status = result.response().statusCode();
      if (status == 200) {
        aliveSomewhere = true;
      } else if (status == 404) {
        // This node no longer holds the instance (it restarted, or its
        // lease expired). Answer "lost" and let the lifecycle hook
        // re-register — one write fans out to every node, so the lost
        // one is repopulated by the same call that heals the fleet.
        lostSomewhere = true;
      } else {
        LOG.warn("Registry node {} answered {} to a heartbeat", result.node(), status);
      }
    }
    return aliveSomewhere && !lostSomewhere;
  }

  /** Removes the instance from every node; one unreachable node must not hold shutdown. */
  public void unregister(ServiceInstance instance) {
    Objects.requireNonNull(instance, "instance");
    List<NodeResult> results;
    try {
      results =
          fanOut(
              "unregister",
              node ->
                  exchange(
                      node,
                      "DELETE",
                      FleetProtocol.unregister(instance.serviceId(), instance.instanceId()),
                      ""));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      LOG.warn("Fleet unregister interrupted; the lease will expire naturally");
      return;
    }
    for (NodeResult result : results) {
      if (result.failure() != null) {
        LOG.warn(
            "Registry node {} unreachable for an unregister request: {}",
            result.node(),
            result.failure().getMessage());
      }
    }
  }

  /**
   * Unions the live nodes' answers by instance id. One unreachable node contributes nothing; every
   * node failing throws — "the fleet is gone" must look like a failure, not like an empty world.
   */
  public List<ServiceInstance> instances(String serviceId) {
    Objects.requireNonNull(serviceId, "serviceId");
    List<NodeResult> results =
        fanOutOrThrow(
            "discover", node -> exchange(node, "GET", FleetProtocol.discover(serviceId), ""));
    Map<String, ServiceInstance> union = new LinkedHashMap<>();
    int failures = 0;
    for (NodeResult result : results) {
      if (!successful(result)) {
        failures++;
        logFailure("discover", result);
        continue;
      }
      try {
        JsonArray entries = JsonUtils.parseArray(result.response().body());
        for (int i = 0; i < entries.size(); i++) {
          ServiceInstance instance = codec.coerce(entries.get(i), ServiceInstance.class);
          if (instance != null) {
            union.putIfAbsent(instance.instanceId(), instance);
          }
        }
      } catch (RuntimeException invalid) {
        failures++;
        LOG.warn(
            "Registry node {} returned an invalid discovery answer: {}",
            result.node(),
            invalid.getMessage());
      }
    }
    if (failures == results.size()) {
      throw new IllegalStateException(
          "no registry node answered a discovery for '" + serviceId + "'");
    }
    if (failures > 0) {
      count("fleet.client.fanout.partial", Map.of("operation", "discover"));
    }
    return List.copyOf(union.values());
  }

  @Override
  public void close() {
    fanout.shutdownNow();
    http.close();
  }

  private List<NodeResult> fanOutOrThrow(String operation, NodeCall call) {
    try {
      return fanOut(operation, call);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fleet " + operation + " interrupted", interrupted);
    }
  }

  /**
   * Runs one request per node concurrently. Per-node failures are returned as data so callers can
   * apply their own quorum policy; an interrupt of the calling thread cancels the remaining calls.
   */
  private List<NodeResult> fanOut(String operation, NodeCall call) throws InterruptedException {
    List<Future<NodeResult>> futures = new ArrayList<>(nodes.addresses().size());
    try {
      for (URI node : nodes.addresses()) {
        futures.add(fanout.submit(() -> callNode(node, call)));
      }
    } catch (RejectedExecutionException closed) {
      for (Future<NodeResult> future : futures) {
        future.cancel(true);
      }
      throw new IllegalStateException("Fleet client is closed", closed);
    }

    List<NodeResult> results = new ArrayList<>(futures.size());
    try {
      for (Future<NodeResult> future : futures) {
        results.add(future.get());
      }
    } catch (InterruptedException interrupted) {
      for (Future<NodeResult> future : futures) {
        future.cancel(true);
      }
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (ExecutionException failed) {
      for (Future<NodeResult> future : futures) {
        future.cancel(true);
      }
      throw new IllegalStateException("Fleet " + operation + " task failed", failed.getCause());
    }

    for (NodeResult result : results) {
      metrics
          .timer("fleet.client.node.request.duration", Map.of("operation", operation))
          .record(result.elapsedNanos());
      if (result.failure() != null) {
        count("fleet.client.node.errors", Map.of("operation", operation, "outcome", "failure"));
      } else if (!successful(result)) {
        count("fleet.client.node.errors", Map.of("operation", operation, "outcome", "http"));
      }
      if (result.failure() instanceof InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      }
    }
    return results;
  }

  private NodeResult callNode(URI node, NodeCall call) {
    long started = System.nanoTime();
    try {
      return new NodeResult(node, call.call(node), null, System.nanoTime() - started);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new NodeResult(node, null, interrupted, System.nanoTime() - started);
    } catch (Exception failed) {
      return new NodeResult(node, null, failed, System.nanoTime() - started);
    }
  }

  private static boolean successful(NodeResult result) {
    return result.failure() == null && result.response().statusCode() / 100 == 2;
  }

  private static int status(NodeResult result) {
    return result.failure() == null ? result.response().statusCode() : -1;
  }

  private void logFailure(String operation, NodeResult result) {
    if (result.failure() != null) {
      LOG.warn(
          "Registry node {} unreachable for a {} request: {}",
          result.node(),
          operation,
          result.failure().getMessage());
      return;
    }
    LOG.warn(
        "Registry node {} answered {} to a {} request",
        result.node(),
        result.response().statusCode(),
        operation);
  }

  private void count(String name, Map<String, String> labels) {
    metrics.counter(name, labels).increment();
  }

  private HttpResponse<String> exchange(URI node, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(node.resolve(path))
            .timeout(nodes.timeout())
            .header("Content-Type", MediaTypes.JSON)
            .header(FleetProtocol.PROTOCOL_HEADER, String.valueOf(FleetProtocol.PROTOCOL));
    if (!nodes.token().isBlank()) {
      request.header(FleetProtocol.TOKEN_HEADER, nodes.token());
    }
    request.method(
        method,
        body.isEmpty()
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 426) {
      // The node announced a protocol this client does not speak. Say so
      // once, with both numbers, instead of letting it read as "refused".
      throw new IllegalStateException(
          "registry node "
              + node
              + " speaks protocol "
              + response.body()
              + " but this client speaks "
              + FleetProtocol.PROTOCOL
              + " — upgrade the client");
    }
    return response;
  }

  @FunctionalInterface
  private interface NodeCall {
    HttpResponse<String> call(URI node) throws IOException, InterruptedException;
  }

  private record NodeResult(
      URI node, HttpResponse<String> response, Exception failure, long elapsedNanos) {}
}
