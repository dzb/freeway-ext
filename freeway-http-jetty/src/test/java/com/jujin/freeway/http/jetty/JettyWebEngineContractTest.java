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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestPipeline;
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
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JettyWebEngineContractTest {

  @Test
  void servesGetWithContentLength() throws Exception {
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = httpClient();
    var routes =
        new RouteIndex(List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))), List.of());
    var pipeline =
        new RequestPipeline(
            routes,
            new WebSocketIndex(List.of(), List.of()),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            List.of());

    try (var server = new WebServer(engine, config, event -> {}, pipeline)) {
      server.start();
      var get =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/ping"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, get.statusCode());
      assertEquals("pong", get.body());
      assertEquals(4, get.headers().firstValueAsLong("Content-Length").orElse(-1));
    }
  }

  @Test
  void headReportsSameContentLengthWithoutBody() throws Exception {
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = httpClient();
    var routes =
        new RouteIndex(List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))), List.of());
    var pipeline =
        new RequestPipeline(
            routes,
            new WebSocketIndex(List.of(), List.of()),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            List.of());

    try (var server = new WebServer(engine, config, event -> {}, pipeline)) {
      server.start();
      var head =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/ping"))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(200, head.statusCode());
      // RFC 7231 §4.3.2: HEAD must report the same Content-Length as GET.
      assertEquals(4, head.headers().firstValueAsLong("Content-Length").orElse(-1));
    }
  }

  @Test
  void sanitizesCorrelationIdForResponseHeaders() {
    assertEquals("abc-123", JettyWebEngine.safeCorrelationId("abc-123"));
    assertTrue(JettyWebEngine.safeCorrelationId("a\r\nInjected: yes").matches("[0-9a-f]{32}"));
    assertTrue(JettyWebEngine.safeCorrelationId("a\nb").matches("[0-9a-f]{32}"));
    assertTrue(JettyWebEngine.safeCorrelationId(null).matches("[0-9a-f]{32}"));
    assertFalse(JettyWebEngine.safeCorrelationId("a\r\nb").contains("\r"));
  }

  private static HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }
}
