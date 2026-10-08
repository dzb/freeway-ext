package com.jujin.freeway.cloud.consul;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The adapter against a stubbed Consul agent (JDK {@code HttpServer}): what it puts on the wire,
 * how it reads a health response back, and the two behaviors the core depends on — {@code renew}
 * answering "not held" with {@code false}, and {@code drainWindow} coming from the wiring.
 *
 * <p>No real Consul here on purpose: the request shape and the {@code Meta} round-trip are what
 * this adapter owns, and both are observable against a stub with no external process. A real-agent
 * run belongs to the gated integration test.
 */
class ConsulClientTest {

  private HttpServer server;
  private int port;

  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicReference<String> token = new AtomicReference<>();
  private volatile int registerStatus = 200;
  private volatile int renewStatus = 200;
  private volatile String instancesBody = "[]";

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          method.set(exchange.getRequestMethod());
          path.set(exchange.getRequestURI().toString());
          body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
          token.set(exchange.getRequestHeaders().getFirst("X-Consul-Token"));

          String requestPath = exchange.getRequestURI().getPath();
          int status;
          String response;
          if (requestPath.startsWith("/v1/agent/service/register")) {
            status = registerStatus;
            response = "";
          } else if (requestPath.startsWith("/v1/agent/check/pass")) {
            status = renewStatus;
            response = "";
          } else if (requestPath.startsWith("/v1/agent/service/deregister")) {
            status = 200;
            response = "";
          } else if (requestPath.startsWith("/v1/health/service")) {
            status = 200;
            response = instancesBody;
          } else {
            status = 404;
            response = "";
          }
          byte[] bytes = response.getBytes(UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private ConsulWiring wiring() {
    return ConsulWiring.defaults().withAgentPort(port);
  }

  @Test
  void registerPutsTheInstanceAndTheBookkeepingMeta() {
    ServiceInstance instance =
        ServiceInstance.of(
            "order", "i1", Endpoint.of("https", "10.0.0.5", 8443, "/api"), Map.of("zone", "a"));

    new ConsulClient(wiring()).register(instance);

    assertEquals("PUT", method.get());
    assertTrue(path.get().startsWith("/v1/agent/service/register"), path.get());
    String sent = body.get();
    assertTrue(sent.contains("\"ID\":\"order:i1\""), sent);
    assertTrue(sent.contains("\"Name\":\"order\""), sent);
    assertTrue(sent.contains("\"Address\":\"10.0.0.5\""), sent);
    assertTrue(sent.contains("\"Port\":8443"), sent);
    // scheme/basePath travel in Meta or an https, path-prefixed service would
    // be mis-called by whoever discovers it.
    assertTrue(sent.contains("\"freeway-scheme\":\"https\""), sent);
    assertTrue(sent.contains("\"freeway-base-path\":\"/api\""), sent);
    assertTrue(sent.contains("\"zone\":\"a\""), sent);
    // A TTL check starts critical; the adapter arms it passing so the
    // instance is discoverable from the moment it registers.
    assertTrue(sent.contains("\"Status\":\"passing\""), sent);
    // Explicit CheckID, or Consul derives "service:{id}" and renew misses it.
    assertTrue(sent.contains("\"CheckID\":\"order:i1\""), sent);
  }

  @Test
  void renewIsTrueOn2xxAndFalseWhenTheAgentNoLongerHoldsIt() {
    ConsulClient client = new ConsulClient(wiring());

    renewStatus = 200;
    assertTrue(client.renew("order", "i1"));
    assertTrue(path.get().contains("/v1/agent/check/pass/order:i1"), path.get());

    renewStatus = 404;
    assertFalse(client.renew("order", "i1"));
  }

  @Test
  void unregisterHitsTheDeregisterEndpoint() {
    new ConsulClient(wiring())
        .unregister(ServiceInstance.of("order", "i1", Endpoint.of("http", "10.0.0.5", 8080)));

    assertTrue(path.get().contains("/v1/agent/service/deregister/order:i1"), path.get());
  }

  @Test
  void instancesReadsBackSchemeBasePathAndAppMetadata() {
    instancesBody =
        "[{\"Service\":{\"ID\":\"order:i1\",\"Name\":\"order\","
            + "\"Address\":\"10.0.0.5\",\"Port\":8443,\"Meta\":{"
            + "\"zone\":\"a\",\"freeway-instance-id\":\"i1\","
            + "\"freeway-scheme\":\"https\",\"freeway-base-path\":\"/api\"}}}]";

    List<ServiceInstance> found = new ConsulClient(wiring()).instances("order");

    assertEquals(1, found.size());
    ServiceInstance instance = found.get(0);
    assertEquals("order", instance.serviceId());
    assertEquals("i1", instance.instanceId());
    assertEquals("https", instance.endpoint().scheme());
    assertEquals("/api", instance.endpoint().basePath());
    assertEquals("10.0.0.5", instance.endpoint().host());
    assertEquals(8443, instance.endpoint().port());
    assertEquals("a", instance.metadata().get("zone"));
    // The bookkeeping keys are the adapter's, not the application's metadata.
    assertFalse(instance.metadata().containsKey("freeway-scheme"));
  }

  @Test
  void instancesDefaultsSchemeAndBasePathWhenMetaIsAbsent() {
    instancesBody =
        "[{\"Service\":{\"ID\":\"order:i9\",\"Name\":\"order\","
            + "\"Address\":\"10.0.0.9\",\"Port\":8080}}]";

    ServiceInstance instance = new ConsulClient(wiring()).instances("order").get(0);

    assertEquals("i9", instance.instanceId(), "recovered from the ID when Meta is absent");
    assertEquals("http", instance.endpoint().scheme());
    assertEquals("", instance.endpoint().basePath());
  }

  @Test
  void tokenHeaderIsSentOnlyWhenConfigured() {
    new ConsulClient(wiring().withToken("secret"))
        .register(ServiceInstance.of("order", "i1", Endpoint.of("http", "10.0.0.5", 8080)));
    assertEquals("secret", token.get());

    new ConsulClient(wiring())
        .register(ServiceInstance.of("order", "i1", Endpoint.of("http", "10.0.0.5", 8080)));
    assertNull(token.get());
  }

  @Test
  void renewThrowsOnAnUnexpectedStatusRatherThanClaimingNotHeld() {
    ConsulClient client = new ConsulClient(wiring());
    renewStatus = 500;
    // 403/5xx is "could not determine", not "not held" — the core marks the
    // node unhealthy on the throw instead of looping a failing re-register.
    assertThrows(IllegalStateException.class, () -> client.renew("order", "i1"));
  }

  @Test
  void instancesSkipsAnEntryWithNoRoutableAddress() {
    instancesBody =
        "[{\"Service\":{\"ID\":\"order:i1\",\"Name\":\"order\","
            + "\"Address\":\"\",\"Port\":0}},"
            + "{\"Service\":{\"ID\":\"order:i2\",\"Name\":\"order\","
            + "\"Address\":\"10.0.0.2\",\"Port\":8080}}]";

    List<ServiceInstance> found = new ConsulClient(wiring()).instances("order");

    // One unroutable entry must not abort discovery for the whole service.
    assertEquals(1, found.size());
    assertEquals("i2", found.get(0).instanceId());
  }

  @Test
  void drainWindowComesFromTheWiring() {
    ConsulServiceRegistry registry =
        new ConsulServiceRegistry(
            new ConsulClient(wiring()), wiring().withDrainWindow(Duration.ofSeconds(7)));
    assertEquals(Duration.ofSeconds(7), registry.drainWindow());
  }
}
