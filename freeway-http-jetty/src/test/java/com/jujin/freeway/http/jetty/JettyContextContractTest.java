/*
 * Copyright 2026 dzb
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jujin.freeway.http.jetty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestComponents;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the Jetty adapter tells the application about the transport, and what it must forget between
 * two requests: a pooled context has to report TLS honestly and must never carry one request's
 * state into the next.
 */
class JettyContextContractTest {
  private static final Path KEYSTORE = keystorePath();
  private static final String PASSWORD = "changeit";

  @AfterEach
  void clearProperties() {
    System.clearProperty("freeway.http.ssl.enabled");
    System.clearProperty("freeway.http.ssl.key-store");
    System.clearProperty("freeway.http.ssl.key-store-password");
    System.clearProperty("freeway.http.ssl.http2");
  }

  @Test
  void plaintextRequestsReportTheTransportHonestly() throws Exception {
    try (var server = start()) {
      var facts = get(plainClient(), "http://127.0.0.1:" + server.port() + "/facts");
      assertEquals(
          "false|false|true",
          facts,
          "plaintext: isSecure=false, no SSL session, client address known");
    }
  }

  @Test
  void keystorePresenceActivatesTlsAndTheFactsFollow() throws Exception {
    // Only the keystore is configured: the shared three-state semantic says a
    // configured keystore is an HTTPS server, and WebServer.secure() reports it
    // — the adapter must not quietly serve plaintext while the framework says
    // otherwise.
    System.setProperty("freeway.http.ssl.key-store", KEYSTORE.toString());
    System.setProperty("freeway.http.ssl.key-store-password", PASSWORD);
    try (var server = start()) {
      var facts = get(tlsClient(), "https://127.0.0.1:" + server.port() + "/facts");
      assertEquals("true|true|true", facts, "TLS: secure, session present, client address known");
    }
  }

  @Test
  void explicitFalseSuppressesAConfiguredKeystore() throws Exception {
    System.setProperty("freeway.http.ssl.enabled", "false");
    System.setProperty("freeway.http.ssl.key-store", KEYSTORE.toString());
    System.setProperty("freeway.http.ssl.key-store-password", PASSWORD);
    try (var server = start()) {
      var facts = get(plainClient(), "http://127.0.0.1:" + server.port() + "/facts");
      assertEquals("false|false|true", facts, "the kill switch serves plaintext");
    }
  }

  @Test
  void unreadableSslEnabledValueFailsNamingTheKey() {
    System.setProperty("freeway.http.ssl.enabled", "maybe");
    var failure =
        assertThrows(
            RuntimeException.class,
            () -> {
              try (var server = start()) {
                server.port();
              }
            });
    assertTrue(
        causes(failure).contains("freeway.http.ssl.enabled"),
        "the failure names the key: " + causes(failure));
    assertTrue(causes(failure).contains("maybe"), "and the value: " + causes(failure));
  }

  @Test
  void contextStateIsFreshWhenTheRequestDoesNotSendACorrelationId() throws Exception {
    // The context is pooled per thread and keeps its own correlation id: a
    // context reused for the next request must not still carry the previous
    // request's id (an absent X-Request-Id leaves setCorrelationId a no-op, so
    // only the metadata reset can clear it).
    try (var server = start()) {
      var client = plainClient();
      String base = "http://127.0.0.1:" + server.port() + "/id";
      var first = client.send(request(base, "leaked-id"), HttpResponse.BodyHandlers.ofString());
      assertEquals("leaked-id", first.body());

      Set<String> seen = new HashSet<>();
      for (int i = 0; i < 10; i++) {
        var response = client.send(request(base, null), HttpResponse.BodyHandlers.ofString());
        assertNotEquals(
            "leaked-id",
            response.body(),
            "request " + i + " carried the previous request's correlation id");
        assertTrue(!response.body().isBlank(), "a generated id is still an id");
        assertTrue(seen.add(response.body()), "each request gets its own id: " + response.body());
      }
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static WebServer start() {
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var server = new WebServer(engine, config, event -> {}, pipeline());
    server.start();
    return server;
  }

  private static HttpRequest request(String uri, String correlationId) {
    var builder = HttpRequest.newBuilder(URI.create(uri)).GET().timeout(Duration.ofSeconds(10));
    if (correlationId != null) {
      builder.header("X-Request-Id", correlationId);
    }
    return builder.build();
  }

  private static String get(HttpClient client, String uri) throws Exception {
    return client.send(request(uri, null), HttpResponse.BodyHandlers.ofString()).body();
  }

  private static HttpClient plainClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  private static HttpClient tlsClient() throws Exception {
    return HttpClient.newBuilder()
        .sslContext(trustingSslContext())
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  private static RequestComponents pipeline() {
    var routes =
        new RouteIndex(
            List.of(
                Route.get("/ping", ctx -> ctx.send(200, "pong")),
                Route.get("/id", ctx -> ctx.send(200, ctx.correlationId())),
                Route.get(
                    "/facts",
                    ctx ->
                        ctx.send(
                            200,
                            ctx.isSecure()
                                + "|"
                                + (ctx.sslSession() != null)
                                + "|"
                                + !ctx.remoteAddress().isBlank()))),
            List.of());
    return new RequestComponents(
        routes,
        new WebSocketIndex(List.of(), List.of()),
        new CorsFilter(false, null, null, null, null, null, false),
        new HealthFilter(false, "/no-health", null),
        List.of(),
        List.of(),
        List.of());
  }

  /** Every message on the cause chain — the key is not always the root. */
  private static String causes(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.append(t.getMessage()).append(" | ");
    }
    return messages.toString();
  }

  private static SSLContext trustingSslContext() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(KEYSTORE)) {
      keyStore.load(in, PASSWORD.toCharArray());
    }
    var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keyStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), null);
    return context;
  }

  private static Path keystorePath() {
    try {
      return Path.of(
          JettyContextContractTest.class.getResource("/freeway-test-keystore.p12").toURI());
    } catch (Exception ex) {
      throw new IllegalStateException("Missing test keystore fixture", ex);
    }
  }
}
