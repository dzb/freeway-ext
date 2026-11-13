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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.testkit.Pipelines;
import com.jujin.freeway.http.testkit.TestServers;
import java.io.IOException;
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

/** TLS startup contract test for the Undertow adapter. */
class UndertowTlsTest {
  private static final Path KEYSTORE = keystorePath();
  private static final String PASSWORD = "changeit";

  @AfterEach
  void clearProperties() {
    System.clearProperty("freeway.http.ssl.enabled");
    System.clearProperty("freeway.http.ssl.key-store");
    System.clearProperty("freeway.http.ssl.key-store-password");
    System.clearProperty("freeway.http.ssl.key-password");
    System.clearProperty("freeway.http.ssl.key-store-type");
    System.clearProperty("freeway.http.ssl.http2");
    System.clearProperty("freeway.http.ssl.protocols");
  }

  @Test
  void servesHttpsWithTls() throws Exception {
    System.setProperty("freeway.http.ssl.enabled", "true");
    System.setProperty("freeway.http.ssl.key-store", KEYSTORE.toString());
    System.setProperty("freeway.http.ssl.key-store-password", PASSWORD);
    var engine = new UndertowHttpEngine(new JsonCodecDefault(), new CoercerDefault());
    var config =
        HttpServerConfig.defaults()
            .withPort(0)
            .withBacklog(64)
            .withShutdownGrace(Duration.ofSeconds(5));
    var client =
        HttpClient.newBuilder()
            .sslContext(trustingSslContext())
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    try (var server = TestServers.start(engine, config, pipeline())) {
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
  void servesHttp2OverTlsByDefault() throws Exception {
    // freeway.http.ssl.http2 defaults to true (same as the built-in engine);
    // Undertow performs ALPN on JDK 9+ so an HTTP/2 client negotiates h2.
    System.setProperty("freeway.http.ssl.enabled", "true");
    System.setProperty("freeway.http.ssl.key-store", KEYSTORE.toString());
    System.setProperty("freeway.http.ssl.key-store-password", PASSWORD);
    var engine = new UndertowHttpEngine(new JsonCodecDefault(), new CoercerDefault());
    var config =
        HttpServerConfig.defaults()
            .withPort(0)
            .withBacklog(64)
            .withShutdownGrace(Duration.ofSeconds(5));
    var client =
        HttpClient.newBuilder()
            .sslContext(trustingSslContext())
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    try (var server = TestServers.start(engine, config, pipeline())) {
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
  void restrictedProtocolsActuallyNarrowTheHandshake() throws Exception {
    // The adapter reads protocols from SslConfig instead of re-splitting the
    // raw key, so this pins that the parsed list reaches XNIO: with only
    // TLSv1.3 offered, a client that requires TLSv1.2 cannot connect.
    System.setProperty("freeway.http.ssl.enabled", "true");
    System.setProperty("freeway.http.ssl.key-store", KEYSTORE.toString());
    System.setProperty("freeway.http.ssl.key-store-password", PASSWORD);
    System.setProperty("freeway.http.ssl.protocols", "TLSv1.3");
    var engine = new UndertowHttpEngine(new JsonCodecDefault(), new CoercerDefault());
    var config =
        HttpServerConfig.defaults()
            .withPort(0)
            .withBacklog(64)
            .withShutdownGrace(Duration.ofSeconds(5));

    try (var server = TestServers.start(engine, config, pipeline())) {
      server.start();
      assertThrows(
          IOException.class,
          () -> handshakeWithTls12Only(server.port()),
          "a TLSv1.2-only client must be refused when the server offers only TLSv1.3");
    }
  }

  /**
   * One TLS handshake attempt that trusts the test certificate but offers only TLSv1.2. The trust
   * setup matters: a client without it fails on the self-signed cert, which would make this probe
   * pass for the wrong reason and hide a server that ignores the protocol list.
   */
  private static void handshakeWithTls12Only(int port) throws IOException {
    SSLContext context;
    try {
      context = trustingSslContext();
    } catch (Exception ex) {
      throw new IOException("Unable to build the client SSL context", ex);
    }
    try (var plain = new java.net.Socket("127.0.0.1", port);
        var socket =
            (javax.net.ssl.SSLSocket)
                context.getSocketFactory().createSocket(plain, "127.0.0.1", port, true)) {
      socket.setSoTimeout(5000);
      socket.setEnabledProtocols(new String[] {"TLSv1.2"});
      socket.startHandshake();
    }
  }

  private static Pipelines pipeline() {
    var routes = List.of(Route.get("/ping", ctx -> ctx.sendText(200, "pong")));
    return Pipelines.of(routes);
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
      return Path.of(UndertowTlsTest.class.getResource("/freeway-test-keystore.p12").toURI());
    } catch (Exception ex) {
      throw new IllegalStateException("Missing test keystore fixture", ex);
    }
  }
}
