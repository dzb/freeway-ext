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

package com.jujin.freeway.http.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestPipeline;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UndertowHttpContractTest {

  private static RequestPipeline pipeline() {
    var routes =
        new RouteIndex(
            List.of(
                Route.get("/ping", ctx -> ctx.send(200, "pong")),
                Route.post(
                    "/echo",
                    ctx -> {
                      ctx.status(200);
                      ctx.output(ctx.body());
                    })),
            List.of());
    return new RequestPipeline(
        routes,
        new WebSocketIndex(List.of(), List.of()),
        new CorsFilter(false, null, null, null, null, null, false),
        new HealthFilter(false, "/no-health", null),
        List.of(),
        List.of(),
        List.of());
  }

  @Test
  void servesGetAndHead() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = httpClient();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
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
  void echoBodyWorksWhenDispatchedToWorker() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = httpClient();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/echo"))
                  .POST(HttpRequest.BodyPublishers.ofString("hello-worker"))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals("hello-worker", new String(response.body(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void sanitizesCorrelationIdForResponseHeaders() {
    assertEquals("abc-123", UndertowWebEngine.safeCorrelationId("abc-123"));
    assertTrue(UndertowWebEngine.safeCorrelationId("a\r\nInjected: yes").matches("[0-9a-f]{32}"));
    assertTrue(UndertowWebEngine.safeCorrelationId("a\nb").matches("[0-9a-f]{32}"));
    assertTrue(UndertowWebEngine.safeCorrelationId(null).matches("[0-9a-f]{32}"));
    assertFalse(UndertowWebEngine.safeCorrelationId("a\r\nb").contains("\r"));
  }

  @Test
  void rejectsCrlfInResponseHeaderName() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = httpClient();
    var captured = new AtomicReference<Throwable>();
    var routes =
        new RouteIndex(
            List.of(Route.get("/bad", ctx -> ctx.setHeader("X-Bad\r\nX-Injected: 1", "v"))),
            List.of());
    var pipeline =
        new RequestPipeline(
            routes,
            new WebSocketIndex(List.of(), List.of()),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            List.of(
                (ctx, ex) -> {
                  captured.set(ex);
                  return false;
                }));

    try (var server = new WebServer(engine, config, event -> {}, pipeline)) {
      server.start();
      client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/bad"))
              .GET()
              .timeout(Duration.ofSeconds(10))
              .build(),
          HttpResponse.BodyHandlers.discarding());
      assertTrue(
          captured.get() instanceof IllegalArgumentException,
          "expected IllegalArgumentException, got: " + captured.get());
    }
  }

  @Test
  void streamsMultipleSseEventsOnOneConnection() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = httpClient();
    // The handler blocks after the first event until the client has actually
    // received it, proving events are streamed on the open connection rather
    // than buffered until the emitter closes.
    var firstEventSeen = new CompletableFuture<Void>();
    var routes =
        new RouteIndex(
            List.of(
                Route.get(
                    "/sse",
                    ctx -> {
                      try (var emitter = ctx.sse()) {
                        emitter.send("one");
                        firstEventSeen.get(5, TimeUnit.SECONDS);
                        emitter.send("two");
                      }
                    })),
            List.of());
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
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/sse"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, resp.statusCode());
      var body = new ByteArrayOutputStream();
      try (var in = resp.body()) {
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) != -1) {
          body.write(buf, 0, n);
          if (body.toString(StandardCharsets.UTF_8).contains("data: one")) {
            firstEventSeen.complete(null);
          }
        }
      }
      String text = body.toString(StandardCharsets.UTF_8);
      assertTrue(text.contains("data: one"), "first SSE event missing: " + text);
      assertTrue(text.contains("data: two"), "second SSE event missing: " + text);
    }
  }

  @Test
  void mapsOversizedBodyToPayloadTooLarge() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, 16 * 1024, Duration.ofSeconds(5), 1024);
    var client = httpClient();
    var routes =
        new RouteIndex(List.of(Route.post("/echo", ctx -> ctx.output(ctx.body()))), List.of());
    var pipeline =
        new RequestPipeline(
            routes,
            new WebSocketIndex(List.of(), List.of()),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            // HttpModule's standard BodyTooLargeException mapping, applied so
            // the adapter+handler pipeline is covered end to end. (With the
            // parser-level MAX_ENTITY_SIZE now set from maxBodySize, Undertow
            // may reject the body at parse time; both paths yield 413.)
            List.of(
                (ctx, ex) -> {
                  if (ex instanceof BodyTooLargeException) {
                    ctx.sendJson(413, java.util.Map.of("error", "Payload Too Large"));
                    return true;
                  }
                  return false;
                }));

    try (var server = new WebServer(engine, config, event -> {}, pipeline)) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/echo"))
                  .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4096)))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, resp.statusCode());
    }
  }

  private static HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }
}
