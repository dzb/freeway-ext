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

package com.jujin.freeway.http.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.http.route.Route;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What an engine adapter tells the application about the transport, and what it must forget between
 * two requests: a pooled context has to report TLS honestly and must never carry one request's
 * state into the next.
 */
public abstract class ContextContract extends EngineFixture {

  private static final String KEYSTORE_RESOURCE = "/freeway-test-keystore.p12";

  @AfterEach
  void clearSslProperties() {
    System.clearProperty("freeway.http.ssl.enabled");
    System.clearProperty("freeway.http.ssl.key-store");
    System.clearProperty("freeway.http.ssl.key-store-password");
    System.clearProperty("freeway.http.ssl.http2");
  }

  @Test
  void plaintextRequestsReportTheTransportHonestly() throws Exception {
    try (var server = start(Pipelines.of(routes()))) {
      var facts = get(HttpClients.plain(), uri(server, "/facts").toString());
      assertEquals(
          "false|false|true",
          facts,
          engineName() + " plaintext: isSecure=false, no SSL session, client address known");
    }
  }

  @Test
  void keystorePresenceActivatesTlsAndTheFactsFollow() throws Exception {
    // Only the keystore is configured: the shared three-state semantic says a
    // configured keystore is an HTTPS server, and HttpServer.secure() reports it
    // — the adapter must not quietly serve plaintext while the framework says
    // otherwise.
    System.setProperty("freeway.http.ssl.key-store", resource(KEYSTORE_RESOURCE).toString());
    System.setProperty("freeway.http.ssl.key-store-password", HttpClients.KEYSTORE_PASSWORD);
    try (var server = start(Pipelines.of(routes()))) {
      var facts =
          get(
              HttpClients.trusting(resource(KEYSTORE_RESOURCE)),
              "https://127.0.0.1:" + server.port() + "/facts");
      assertEquals(
          "true|true|true",
          facts,
          engineName() + " TLS: secure, session present, client address known");
    }
  }

  @Test
  void explicitFalseSuppressesAConfiguredKeystore() throws Exception {
    System.setProperty("freeway.http.ssl.enabled", "false");
    System.setProperty("freeway.http.ssl.key-store", resource(KEYSTORE_RESOURCE).toString());
    System.setProperty("freeway.http.ssl.key-store-password", HttpClients.KEYSTORE_PASSWORD);
    try (var server = start(Pipelines.of(routes()))) {
      var facts = get(HttpClients.plain(), uri(server, "/facts").toString());
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
              try (var server = start(Pipelines.of(routes()))) {
                server.port();
              }
            });
    assertTrue(
        HttpClients.causes(failure).contains("freeway.http.ssl.enabled"),
        "the failure names the key: " + HttpClients.causes(failure));
    assertTrue(
        HttpClients.causes(failure).contains("maybe"),
        "and the value: " + HttpClients.causes(failure));
  }

  @Test
  void contextStateIsFreshWhenTheRequestDoesNotSendACorrelationId() throws Exception {
    // The context is pooled per thread and keeps its own correlation id: a
    // context reused for the next request must not still carry the previous
    // request's id (an absent X-Request-Id leaves setCorrelationId a no-op, so
    // only the metadata reset can clear it).
    try (var server = start(Pipelines.of(routes()))) {
      var client = HttpClients.plain();
      String base = uri(server, "/id").toString();
      var first =
          client.send(HttpClients.request(base, "leaked-id"), HttpResponse.BodyHandlers.ofString());
      assertEquals("leaked-id", first.body());

      Set<String> seen = new HashSet<>();
      for (int i = 0; i < 10; i++) {
        var response =
            client.send(HttpClients.request(base, null), HttpResponse.BodyHandlers.ofString());
        assertNotEquals(
            "leaked-id",
            response.body(),
            "request " + i + " carried the previous request's correlation id");
        assertTrue(!response.body().isBlank(), "a generated id is still an id");
        assertTrue(seen.add(response.body()), "each request gets its own id: " + response.body());
      }
    }
  }

  @Test
  void oversizedBodyIsRejectedByTheSharedAccounting() throws Exception {
    // maxBodySize must answer the same 413 whatever layer trips first: the
    // shared AbstractHttpContext.readBody accounting, or Undertow's native
    // MAX_ENTITY_SIZE mapped from the same config (surfaced as the shared
    // BodyTooLargeException). Never a dropped connection, never a body
    // truncated to look complete.
    var config = defaultConfig().withMaxBodySize(16);
    try (var server = start(Pipelines.of(routes()), config)) {
      var resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, "/echo"))
                      .POST(HttpRequest.BodyPublishers.ofString("x".repeat(64)))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(
          413,
          resp.statusCode(),
          engineName() + " must answer the shared maxBodySize accounting with 413");
    }
  }

  private static String get(HttpClient client, String uri) throws Exception {
    return client.send(HttpClients.request(uri, null), HttpResponse.BodyHandlers.ofString()).body();
  }

  private static List<Route> routes() {
    return List.of(
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
                        + !ctx.remoteAddress().isBlank())),
        Route.post(
            "/echo",
            ctx -> {
              ctx.body();
              ctx.send(200, "ok");
            }));
  }
}
