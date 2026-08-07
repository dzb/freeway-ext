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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TLS and HTTP/2 startup contract tests for the Jetty adapter. */
class JettyTlsHttp2Test {
  private static final Path KEYSTORE = keystorePath();
  private static final String PASSWORD = "changeit";

  @AfterEach
  void clearProperties() {
    System.clearProperty("freeway.http.ssl.enabled");
    System.clearProperty("freeway.http.ssl.key-store");
    System.clearProperty("freeway.http.ssl.key-store-password");
    System.clearProperty("freeway.http.ssl.key-password");
    System.clearProperty("freeway.http.http2");
  }

  @Test
  void servesHttpsWithTls() throws Exception {
    enableTls();
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client =
        HttpClient.newBuilder()
            .sslContext(trustingSslContext())
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/ping"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resp.statusCode());
      assertEquals("pong", resp.body());
    }
  }

  @Test
  void servesHttp2OverTlsWithAlpn() throws Exception {
    enableTls();
    System.setProperty("freeway.http.http2", "true");
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client =
        HttpClient.newBuilder()
            .sslContext(trustingSslContext())
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/ping"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resp.statusCode());
      assertEquals("pong", resp.body());
      assertEquals(HttpClient.Version.HTTP_2, resp.version());
    }
  }

  @Test
  void servesHttp2Cleartext() throws Exception {
    System.setProperty("freeway.http.http2", "true");
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    try (var server = new WebServer(engine, config, event -> {}, pipeline())) {
      server.start();
      var resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/ping"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, resp.statusCode());
      assertEquals("pong", resp.body());
      assertEquals(HttpClient.Version.HTTP_2, resp.version());
    }
  }

  private static void enableTls() {
    System.setProperty("freeway.http.ssl.enabled", "true");
    System.setProperty("freeway.http.ssl.key-store", KEYSTORE.toString());
    System.setProperty("freeway.http.ssl.key-store-password", PASSWORD);
  }

  private static RequestPipeline pipeline() {
    var routes =
        new RouteIndex(List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))), List.of());
    return new RequestPipeline(
        routes,
        new WebSocketIndex(List.of(), List.of()),
        new CorsFilter(false, null, null, null, null, null, false),
        new HealthFilter(false, "/no-health", null),
        List.of(),
        List.of(),
        List.of());
  }

  /** Trusts the test certificate (self-signed) for the HTTPS client. */
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
      return Path.of(JettyTlsHttp2Test.class.getResource("/freeway-test-keystore.p12").toURI());
    } catch (Exception ex) {
      throw new IllegalStateException("Missing test keystore fixture", ex);
    }
  }
}
