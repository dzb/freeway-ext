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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerImpl;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestComponents;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** gzip response-compression contract tests for the Undertow adapter. */
class UndertowCompressionTest {
  private static final String BIG_BODY = "x".repeat(4096);

  @Test
  void compressesLargeBufferedResponseWhenClientAcceptsGzip() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerImpl());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = HttpClient.newHttpClient();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/big"))
                  .header("Accept-Encoding", "gzip")
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, resp.statusCode());
      assertEquals("gzip", resp.headers().firstValue("Content-Encoding").orElse(null));
      assertTrue(
          resp.headers().firstValue("Vary").orElse("").contains("Accept-Encoding"),
          "Vary must advertise Accept-Encoding");
      // The user-set Accept-Encoding header disables the client's automatic
      // decompression, so the raw gzip payload must round-trip.
      assertArrayEquals(BIG_BODY.getBytes(StandardCharsets.UTF_8), gunzip(resp.body()));
    }
  }

  @Test
  void doesNotCompressSmallResponseBelowMinSize() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerImpl());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = HttpClient.newHttpClient();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/small"))
                  .header("Accept-Encoding", "gzip")
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resp.statusCode());
      assertFalse(resp.headers().firstValue("Content-Encoding").isPresent());
      assertEquals(4, resp.headers().firstValueAsLong("Content-Length").orElse(-1));
      assertEquals("pong", resp.body());
    }
  }

  @Test
  void doesNotCompressWithoutAcceptEncoding() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerImpl());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client = HttpClient.newHttpClient();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/big"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resp.statusCode());
      assertFalse(resp.headers().firstValue("Content-Encoding").isPresent());
      assertEquals(BIG_BODY, resp.body());
      assertEquals(BIG_BODY.length(), resp.headers().firstValueAsLong("Content-Length").orElse(-1));
    }
  }

  @Test
  void compressionDisabledByConfig() throws Exception {
    var engine = new UndertowWebEngine(new JsonCodecDefault(), new CoercerImpl());
    var config =
        HttpServerConfig.builder()
            .port(0)
            .compression(new HttpServerConfig.CompressionConfig(false, 0))
            .build();
    var client = HttpClient.newHttpClient();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/big"))
                  .header("Accept-Encoding", "gzip")
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resp.statusCode());
      assertFalse(resp.headers().firstValue("Content-Encoding").isPresent());
      assertEquals(BIG_BODY, resp.body());
    }
  }

  private static byte[] gunzip(byte[] data) throws Exception {
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return gzip.readAllBytes();
    }
  }

  private static RequestComponents pipeline() {
    var routes =
        new RouteIndex(
            List.of(
                Route.get("/big", ctx -> ctx.send(200, BIG_BODY)),
                Route.get("/small", ctx -> ctx.send(200, "pong"))),
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
}
