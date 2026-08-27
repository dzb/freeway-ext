package com.jujin.freeway.http.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.rpc.CloudException;
import com.jujin.freeway.cloud.rpc.RemoteCaller;
import com.jujin.freeway.cloud.rpc.RemoteInvocationException;
import com.jujin.freeway.cloud.rpc.RpcEndpoint;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestComponents;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.body.MultipartException;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.ioc.CallBus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Design-doc phase D: the remote-CallBus bridge works over the Undertow
 * engine. The server side registers CallBus handlers and exports them via
 * {@link RpcEndpoint}; the client drives {@link RemoteCaller} against the
 * container's own registry so the whole exchange is engine-real but
 * self-contained.
 */
class UndertowRemoteRpcTest {

  static class BizFailure extends RuntimeException {
    BizFailure(String m) { super(m); }
  }

  /** Handlers must be public: CallBus reflective dispatch honors access rules. */
  public static class Handlers {
    public String greet(String name) { return "hi " + name; }
    public int add(int a, int b) { return a + b; }
    public String boom() { throw new BizFailure("overdrawn"); }
  }

  private WebServer server;
  private RemoteCaller caller;
  private CallBus bus;

  /** Minimal Container serving exactly what CallBus construction needs. */
  static class NoopContainer implements com.jujin.freeway.ioc.Container {
    private final com.jujin.freeway.commons.metrics.Metrics metrics
        = new com.jujin.freeway.commons.metrics.NoopMetrics();
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
      if (type == com.jujin.freeway.commons.metrics.Metrics.class) return (T) metrics;
      throw new UnsupportedOperationException(String.valueOf(type));
    }
    public <T> T get(Class<T> type, String id) { return get(type); }
    @SafeVarargs public final <T> T get(Class<T> type, Class<? extends java.lang.annotation.Annotation>... markers) { return get(type); }
    public <T> com.jujin.freeway.ioc.extension.Extension<T> extension(Class<T> entryType) {
      throw new UnsupportedOperationException();
    }
    public <T> T create(Class<T> type) { throw new UnsupportedOperationException(); }
    public void close() {}
  }

  @AfterEach
  void stop() {
    if (server != null) server.close();
  }

  private void start() {
    bus = new CallBus(new NoopContainer());
    bus.register("user", new Handlers());

    var routes =
        new RouteIndex(
            List.of(RpcEndpoint.of("user", bus, new JsonCodecDefault())), List.of());
    var pipeline =
        new RequestComponents(
            routes,
            new com.jujin.freeway.http.websocket.WebSocketIndex(List.of(), List.of()),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            List.of());

    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    server = new WebServer(engine, config, event -> {}, pipeline);
    server.start();


    // RemoteCaller needs CloudHttpClient; build a minimal standalone stack:
    var discovery =
        (com.jujin.freeway.cloud.discovery.ServiceDiscovery)
            serviceId ->
                List.of(
                    com.jujin.freeway.cloud.discovery.ServiceInstance.of(
                        serviceId,
                        "i1",
                        com.jujin.freeway.cloud.discovery.Endpoint.of(
                            "http", "127.0.0.1", server.port()),
                        java.util.Map.of()));
    var loadBalancer =
        (com.jujin.freeway.cloud.discovery.LoadBalancer)
            instances -> instances.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(instances.get(0));
    var cloudClient =
        new com.jujin.freeway.cloud.internal.CloudHttpClientDefault(
            discovery,
            loadBalancer,
            List.of(), // no propagators in this test
            null, null, null, // no retry/breaker/rate-limit
            com.jujin.freeway.cloud.rpc.TransportSecurity.NONE,
            null, // no tracer
            new com.jujin.freeway.commons.metrics.NoopMetrics(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2));
    caller = new RemoteCaller(cloudClient, new JsonCodecDefault());
  }

  @Test
  void roundTripOverUndertow() {
    start();
    assertEquals("hi bob", caller.invoke("target", "user", "greet", List.of("bob"), String.class));
  }

  @Test
  void primitiveArgsSurviveTheWire() {
    start();
    assertEquals(42, caller.invoke("target", "user", "add", List.of(2, 40), Integer.class));
  }

  @Test
  void businessFailureMapsToNonRetryableWithRemoteClass() {
    start();
    CloudException ex =
        assertThrows(
            CloudException.class,
            () -> caller.invoke("target", "user", "boom", List.of(), String.class));
    assertFalse(ex.retryable());
    Object cause = ex.getCause();
    assertTrue(cause instanceof RemoteInvocationException,
        "cause must be the rebuilt remote exception, got: " + cause);
    assertEquals(BizFailure.class.getName(), ((RemoteInvocationException) cause).remoteClass());
  }

  @Test
  void unknownTopicIs404() {
    start();
    CloudException ex =
        assertThrows(
            CloudException.class,
            () -> caller.invoke("target", "user", "missing", List.of(), String.class));
    assertEquals(404, ex.status());
  }
}
