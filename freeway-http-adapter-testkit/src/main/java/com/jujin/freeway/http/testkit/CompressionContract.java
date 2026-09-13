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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.route.Route;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gzip response contract every engine adapter shares: compress only when the client asked for
 * it, only above the configured size, and never when the configuration disables it.
 */
public abstract class CompressionContract extends EngineFixture {

  protected static final String BIG_BODY = "x".repeat(4096);

  @Test
  void compressesLargeBufferedResponseWhenClientAcceptsGzip() throws Exception {
    var client = HttpClient.newHttpClient();

    try (var server = start(Pipelines.of(routes()))) {
      var resp =
          client.send(
              HttpRequest.newBuilder(uri(server, "/big"))
                  .header("Accept-Encoding", "gzip")
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, resp.statusCode());
      assertEquals(
          "gzip",
          resp.headers().firstValue("Content-Encoding").orElse(null),
          engineName() + " must compress when the client accepts gzip");
      assertTrue(
          resp.headers().firstValue("Vary").orElse("").contains("Accept-Encoding"),
          "Vary must advertise Accept-Encoding");
      // The user-set Accept-Encoding header disables the client's automatic
      // decompression, so the raw gzip payload must round-trip.
      assertArrayEquals(BIG_BODY.getBytes(StandardCharsets.UTF_8), HttpClients.gunzip(resp.body()));
    }
  }

  @Test
  void doesNotCompressSmallResponseBelowMinSize() throws Exception {
    var client = HttpClient.newHttpClient();

    try (var server = start(Pipelines.of(routes()))) {
      var resp =
          client.send(
              HttpRequest.newBuilder(uri(server, "/small"))
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
    var client = HttpClient.newHttpClient();

    try (var server = start(Pipelines.of(routes()))) {
      var resp =
          client.send(
              HttpRequest.newBuilder(uri(server, "/big"))
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
    var client = HttpClient.newHttpClient();
    var config =
        HttpServerConfig.builder()
            .port(0)
            .compression(new HttpServerConfig.CompressionConfig(false, 0))
            .build();

    try (var server = start(Pipelines.of(routes()), config)) {
      var resp =
          client.send(
              HttpRequest.newBuilder(uri(server, "/big"))
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

  private static List<Route> routes() {
    return List.of(
        Route.get("/big", ctx -> ctx.send(200, BIG_BODY)),
        Route.get("/small", ctx -> ctx.send(200, "pong")));
  }
}
