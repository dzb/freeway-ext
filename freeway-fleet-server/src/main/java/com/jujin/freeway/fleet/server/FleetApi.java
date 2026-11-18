package com.jujin.freeway.fleet.server;

import com.jujin.freeway.cloud.discovery.ServiceHealth;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.fleet.FleetCodec;
import com.jujin.freeway.fleet.FleetProtocol;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP surface of a Fleet Server.
 *
 * <p>It is written against the {@link LeaseStore}: the server publishes its own lease store, and
 * the store is the one subject these routes describe. The discovery view is not a substitute — it
 * returns ready instances only, so a warming instance could not be deregistered through it.
 */
final class FleetApi implements RouteHandler {

  private static final Logger LOG = LoggerFactory.getLogger(FleetApi.class);

  private final LeaseStore store;
  private final FleetServerOptions options;
  private final Metrics metrics;
  private final JsonCodec codec = FleetCodec.JSON;

  /**
   * Read per request, not captured at construction: the token is the one setting an operator
   * rotates on a live node — a rotate-by-restart rule would make every secret change an outage.
   */
  private final SymbolSource symbols;

  FleetApi(LeaseStore store, SymbolSource symbols, FleetServerOptions options, Metrics metrics) {
    this.store = Objects.requireNonNull(store, "store");
    this.symbols = Objects.requireNonNull(symbols, "symbols");
    this.options = Objects.requireNonNull(options, "options");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  /** True when {@code announced} is a non-negative protocol this node speaks: older or equal. */
  private static boolean supports(String announced) {
    try {
      int version = Integer.parseInt(announced.trim());
      return version >= 0 && version <= FleetProtocol.PROTOCOL;
    } catch (NumberFormatException notANumber) {
      return false;
    }
  }

  @Override
  public void handle(HttpExchange ctx) throws IOException {
    long started = System.nanoTime();
    try {
      handleRequest(ctx);
    } finally {
      metrics.timer("fleet.server.request.duration").record(System.nanoTime() - started);
    }
  }

  private void handleRequest(HttpExchange ctx) throws IOException {
    metrics.counter("fleet.server.requests").increment();
    // Version first: a client that speaks a newer protocol gets a readable
    // 426 here instead of a 404 on whichever route moved. Older protocols
    // are accepted — the documented promise is "a server keeps accepting
    // older clients", and equality made a proto-0 client a 426.
    String announced = ctx.header(FleetProtocol.PROTOCOL_HEADER).orElse("");
    if (!announced.isEmpty() && !supports(announced)) {
      metrics.counter("fleet.server.protocol.rejects").increment();
      ctx.setHeader(FleetProtocol.PROTOCOL_HEADER, String.valueOf(FleetProtocol.PROTOCOL));
      ctx.sendJson(
          HttpStatus.UPGRADE_REQUIRED,
          Map.of(
              "error",
              "unsupported registry protocol " + announced,
              "supported",
              FleetProtocol.PROTOCOL));
      return;
    }
    ctx.setHeader(FleetProtocol.PROTOCOL_HEADER, String.valueOf(FleetProtocol.PROTOCOL));

    if (!FleetAuth.authorized(ctx, symbols)) {
      metrics.counter("fleet.server.auth.failures").increment();
      // A shared-secret check, not an identity system: the node is
      // reachable inside a trusted network, and the token keeps an
      // accidental writer from registering into it.
      ctx.sendText(HttpStatus.UNAUTHORIZED, "");
      return;
    }
    String method = ctx.method();
    String serviceId = ctx.pathVar("serviceId").orElse(null);
    String instanceId = ctx.pathVar("instanceId").orElse(null);
    try {
      if ("GET".equalsIgnoreCase(method)) {
        metrics.counter("fleet.server.discover.requests").increment();
        discover(ctx, serviceId);
      } else if ("DELETE".equalsIgnoreCase(method)) {
        metrics.counter("fleet.server.unregister.requests").increment();
        unregister(ctx, serviceId, instanceId);
      } else if ("POST".equalsIgnoreCase(method)) {
        if (ctx.path().endsWith("/renew")) {
          metrics.counter("fleet.server.renew.requests").increment();
          renew(ctx, serviceId, instanceId);
        } else if (ctx.path().endsWith("/ready")) {
          metrics.counter("fleet.server.ready.requests").increment();
          ready(ctx, serviceId, instanceId);
        } else {
          metrics.counter("fleet.server.register.requests").increment();
          register(ctx);
        }
      } else {
        ctx.sendText(HttpStatus.METHOD_NOT_ALLOWED, "");
      }
    } catch (IllegalArgumentException invalid) {
      // A malformed payload or readiness value is the caller's input error.
      ctx.sendJson(HttpStatus.BAD_REQUEST, Map.of("error", invalid.getMessage()));
    } catch (RuntimeException failed) {
      // Store/connection failures are not client errors. Keep the details in the
      // server log and expose only a stable message to the caller.
      metrics.counter("fleet.server.internal.errors").increment();
      LOG.error("Fleet request failed", failed);
      ctx.sendJson(HttpStatus.INTERNAL_ERROR, Map.of("error", "fleet server failed"));
    }
  }

  private void discover(HttpExchange ctx, String serviceId) throws IOException {
    ctx.sendJson(HttpStatus.OK, store.liveReadyInstances(serviceId, options.leaseTtl()));
  }

  private void register(HttpExchange ctx) throws IOException {
    String json = ctx.bodyText();
    ServiceInstance instance = codec.fromJson(json, ServiceInstance.class);
    if (instance == null) {
      throw new IllegalArgumentException("service instance body is required");
    }
    // A client that already knows it is warming registers as such in one
    // write (`?ready=false`), instead of registering as ready and being
    // marked not-ready a moment later — a window in which discovery could
    // route traffic to it. Absent means ready, the pre-existing contract.
    Boolean ready = requestedReadiness(ctx);
    store.register(
        instance, ready != null && !ready ? ServiceHealth.starting() : ServiceHealth.up());
    ctx.sendJson(HttpStatus.OK, instance);
  }

  /**
   * Heartbeat. The optional body carries readiness ({@code {"ready":true}}), so the bit rides the
   * heartbeat an instance already sends — no extra round trip. A node that ignores the body keeps
   * the plain heartbeat.
   */
  private void renew(HttpExchange ctx, String serviceId, String instanceId) throws IOException {
    Boolean ready = requestedReadiness(ctx);
    boolean alive =
        ready == null
            ? store.renew(serviceId, instanceId)
            : store.renew(serviceId, instanceId, ready);
    ctx.sendJson(alive ? HttpStatus.OK : HttpStatus.NOT_FOUND, Map.of("alive", alive));
  }

  /**
   * Explicit readiness flip, so a starting instance becomes discoverable at the moment it is ready
   * rather than at the next heartbeat.
   */
  private void ready(HttpExchange ctx, String serviceId, String instanceId) throws IOException {
    Boolean ready = requestedReadiness(ctx);
    boolean applied = ready != null && store.markReady(serviceId, instanceId, ready);
    ctx.sendJson(
        applied ? HttpStatus.OK : HttpStatus.NOT_FOUND,
        Map.of("ready", Boolean.TRUE.equals(ready)));
  }

  private void unregister(HttpExchange ctx, String serviceId, String instanceId)
      throws IOException {
    // By id, straight at the store: an instance that is still warming must
    // be removable, and the discovery view deliberately hides those.
    boolean removed = store.unregister(serviceId, instanceId);
    ctx.sendJson(removed ? HttpStatus.OK : HttpStatus.NOT_FOUND, Map.of("unregistered", removed));
  }

  /** Reads {@code {"ready":…}} from the body, or {@code ?ready=} from the query. */
  private Boolean requestedReadiness(HttpExchange ctx) throws IOException {
    String body = ctx.bodyText();
    if (body != null && !body.isBlank()) {
      JsonObject node = JsonUtils.parseObject(body);
      Boolean fromBody = node == null ? null : node.getBoolean("ready");
      if (fromBody != null) {
        return fromBody;
      }
    }
    String query = ctx.queryParam("ready").orElse(null);
    if (query == null) {
      return null;
    }
    if ("true".equalsIgnoreCase(query)) {
      return true;
    }
    if ("false".equalsIgnoreCase(query)) {
      return false;
    }
    throw new IllegalArgumentException("ready must be true or false: " + query);
  }
}
