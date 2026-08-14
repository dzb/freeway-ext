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

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.websocket.*;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;
import org.xnio.Sequence;
import org.xnio.SslClientAuthMode;

/** Undertow transport adapter for the Freeway HTTP engine. */
public final class UndertowWebEngine implements HttpEngine {
  private static final Logger LOG = LoggerFactory.getLogger(UndertowWebEngine.class);
  private static final HttpString X_REQUEST_ID = new HttpString("X-Request-Id");
  private static final String INTERNAL_ERROR_BODY = "Internal Server Error";

  private final JsonCodec jsonCodec;
  private final Coercer coercer;
  private final ThreadLocal<UndertowHttpContext> contextPool;
  private volatile long wsMaxMessageSize = -1;

  public UndertowWebEngine(JsonCodec jsonCodec, Coercer coercer) {
    this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    this.coercer = Objects.requireNonNull(coercer, "coercer");
    this.contextPool =
        ThreadLocal.withInitial(() -> new UndertowHttpContext(this.jsonCodec, this.coercer));
  }

  @Override
  public HttpServerHandle start(HttpServerConfig config, ExchangeHandler handler) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(handler, "handler");

    // Freeway handlers are application code that may block (body reads, DB
    // access, downstream calls). Undertow invokes the root handler on an I/O
    // thread, so dispatch to the worker pool before running application code.
    // Trade-off: every request pays one thread hand-off; correctness (no
    // blocking on I/O threads) is preferred over raw adapter throughput.
    // Set freeway.http.undertow.dispatch-io=false to run handlers directly
    // on I/O threads (faster for non-blocking handlers, dangerous if they
    // block).
    boolean dispatchIo =
        !"false".equalsIgnoreCase(System.getProperty("freeway.http.undertow.dispatch-io", "true"));
    // Same knob as the Jetty adapter: maximum WebSocket text/binary message
    // size in bytes; 0 or negative disables the limit. Undertow's default is
    // unlimited, which lets a remote client buffer unbounded messages (OOM).
    this.wsMaxMessageSize = Long.getLong("freeway.http.websocket.max-frame-size", 65_536L);
    HttpHandler root =
        exchange -> {
          if (dispatchIo && exchange.isInIoThread()) {
            exchange.dispatch(() -> handle(exchange, handler, config));
          } else {
            handle(exchange, handler, config);
          }
        };
    // gzip response compression (HttpServerConfig.compression) is applied in
    // UndertowHttpContext.output(), mirroring the built-in engine's buffered
    // path (min-size, status, Accept-Encoding and Content-Type gates). A
    // handler-level EncodingHandler is deliberately not used: Undertow's
    // dynamic encoding has no minimum-size gate, so it would compress small
    // responses the shared config says to leave alone.
    GracefulShutdownHandler gracefulShutdown = Handlers.gracefulShutdown(root);
    boolean sslEnabled = Boolean.getBoolean(HttpConfigKeys.SSL_ENABLED);
    Undertow.Builder builder =
        Undertow.builder()
            .setHandler(gracefulShutdown)
            .setIoThreads(Runtime.getRuntime().availableProcessors())
            // Undertow's defaults leave connections without idle/parse
            // deadlines and allow a 1 MiB header budget, exposing slow-loris
            // and per-connection memory abuse. Map the shared Freeway config
            // (readTimeout, backlog, socket buffers) onto Undertow's options;
            // maxConnections and writeTimeout have no Undertow equivalent.
            .setServerOption(UndertowOptions.IDLE_TIMEOUT, (int) config.readTimeout().toMillis())
            .setServerOption(
                UndertowOptions.REQUEST_PARSE_TIMEOUT, (int) config.readTimeout().toMillis())
            .setServerOption(UndertowOptions.MAX_HEADER_SIZE, 64 * 1024)
            .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, config.maxBodySize())
            .setServerOption(UndertowOptions.MULTIPART_MAX_ENTITY_SIZE, config.maxBodySize());
    if (config.backlog() > 0) {
      builder.setSocketOption(Options.BACKLOG, config.backlog());
    }
    if (config.receiveBufferSize() > 0) {
      builder.setSocketOption(Options.RECEIVE_BUFFER, config.receiveBufferSize());
    }
    if (config.sendBufferSize() > 0) {
      builder.setSocketOption(Options.SEND_BUFFER, config.sendBufferSize());
    }
    // Undertow defaults to workerThreads = ioThreads * 8; the old explicit
    // 1-thread worker pool starved blocking handlers.
    if (sslEnabled) {
      builder.addHttpsListener(config.port(), config.host(), sslContext());
      // mTLS / protocol / cipher constraints, keyed on the same
      // freeway.http.ssl.* options the built-in engine uses. XNIO applies
      // these socket options when the SSL engine is created.
      if (Boolean.getBoolean(HttpConfigKeys.SSL_CLIENT_AUTH)) {
        builder.setSocketOption(Options.SSL_CLIENT_AUTH_MODE, SslClientAuthMode.REQUIRED);
      }
      String protocols = prop(HttpConfigKeys.SSL_PROTOCOLS);
      if (protocols != null && !protocols.isBlank()) {
        builder.setSocketOption(
            Options.SSL_ENABLED_PROTOCOLS, Sequence.of(splitCommaSeparated(protocols)));
      }
      String ciphers = prop(HttpConfigKeys.SSL_CIPHERS);
      if (ciphers != null && !ciphers.isBlank()) {
        builder.setSocketOption(
            Options.SSL_ENABLED_CIPHER_SUITES, Sequence.of(splitCommaSeparated(ciphers)));
      }
      // HTTP/2 over TLS via ALPN, keyed on the same freeway.http.ssl.http2
      // flag the built-in engine uses (default true). Undertow performs ALPN
      // itself on JDK 9+; h2c (cleartext) has no core knob and is not
      // enabled by this adapter.
      if (!"false".equalsIgnoreCase(System.getProperty(HttpConfigKeys.SSL_HTTP2, "true"))) {
        builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true);
      }
    } else {
      builder.addHttpListener(config.port(), config.host());
    }
    Undertow server = builder.build();
    server.start();
    LOG.info(
        "Freeway undertow web engine started on {}:{} (http2={})",
        config.host(),
        listenerPort(server),
        sslEnabled
            && !"false".equalsIgnoreCase(System.getProperty(HttpConfigKeys.SSL_HTTP2, "true")));
    return new UndertowHandle(server, gracefulShutdown, config.shutdownGrace(), config.host());
  }

  /**
   * Builds a TLS context from the shared {@code freeway.http.ssl.*} keys, mirroring the built-in
   * engine's {@code SslContextFactory}: keystore type from {@code freeway.http.ssl.key-store-type}
   * (inferred from the file extension when unset), optional truststore for peer validation, and
   * client-auth/protocols/ciphers enforced via XNIO socket options (the JDK SSLContext itself is
   * configured with key/trust managers only).
   */
  private static SSLContext sslContext() {
    String keyStorePath = prop(HttpConfigKeys.SSL_KEY_STORE);
    char[] password = prop(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "").toCharArray();
    try {
      String type = keyStoreType(keyStorePath);
      KeyStore keyStore = KeyStore.getInstance(type);
      try (var in = Files.newInputStream(Path.of(keyStorePath))) {
        keyStore.load(in, password);
      }
      // JKS keystores may protect the key with a separate password; honor
      // freeway.http.ssl.key-password like the Jetty adapter does.
      String keyPasswordProp = System.getProperty("freeway.http.ssl.key-password");
      char[] keyPassword = keyPasswordProp != null ? keyPasswordProp.toCharArray() : password;
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, keyPassword);
      KeyManager[] keyManagers = kmf.getKeyManagers();

      TrustManager[] trustManagers = null;
      String trustStorePath = prop(HttpConfigKeys.SSL_TRUST_STORE);
      if (trustStorePath != null) {
        String trustStoreType = prop(HttpConfigKeys.SSL_TRUST_STORE_TYPE, "PKCS12");
        char[] trustPassword = prop(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, "").toCharArray();
        KeyStore trustStore = KeyStore.getInstance(trustStoreType);
        try (var in = Files.newInputStream(Path.of(trustStorePath))) {
          trustStore.load(in, trustPassword);
        }
        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        trustManagers = tmf.getTrustManagers();
      }

      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keyManagers, trustManagers, null);
      return context;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to configure TLS for Undertow", ex);
    }
  }

  /**
   * Resolves the keystore type: explicit {@code freeway.http.ssl.key-store-type} wins, else
   * inferred from the {@code .jks} extension, else PKCS12 (the built-in engine's default).
   */
  private static String keyStoreType(String keyStorePath) {
    String explicit = prop(HttpConfigKeys.SSL_KEY_STORE_TYPE);
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }
    return keyStorePath != null && keyStorePath.toLowerCase(Locale.ROOT).endsWith(".jks")
        ? "JKS"
        : "PKCS12";
  }

  private static String prop(String key) {
    return System.getProperty(key);
  }

  private static String prop(String key, String defaultValue) {
    return System.getProperty(key, defaultValue);
  }

  /** Splits a comma-separated property value into trimmed, non-empty tokens. */
  private static String[] splitCommaSeparated(String value) {
    return java.util.Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
  }

  private void handle(
      HttpServerExchange exchange, ExchangeHandler handler, HttpServerConfig config) {
    try {
      dispatch(exchange, handler, config);
    } catch (Exception ex) {
      LOG.error("Undertow request failed for {} {}", method(exchange), path(exchange), ex);
      if (!exchange.isResponseStarted()) {
        exchange.setStatusCode(500);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, MediaTypes.TEXT_PLAIN_UTF8);
        exchange.getResponseSender().send(INTERNAL_ERROR_BODY);
      } else {
        // Response was already started by the handler before it failed;
        // writing a 500 now would throw. Just end the exchange.
        exchange.endExchange();
      }
    }
  }

  private void dispatch(
      HttpServerExchange exchange, ExchangeHandler handler, HttpServerConfig config)
      throws Exception {
    String correlationId = exchange.getRequestHeaders().getFirst("X-Request-Id");
    exchange.getResponseHeaders().put(X_REQUEST_ID, safeCorrelationId(correlationId));
    if (isWebSocketRequest(exchange)) {
      String origin = exchange.getRequestHeaders().getFirst(Headers.ORIGIN);
      WebSocketMatch match = handler.websocket(method(exchange), path(exchange), origin);
      if (match == null) {
        ResponseCodeHandler.HANDLE_404.handleRequest(exchange);
        return;
      }
      handleWebSocket(exchange, correlationId, match, wsMaxMessageSize);
      return;
    }

    UndertowHttpContext ctx = contextPool.get();
    ctx.reset(exchange, correlationId);
    ctx.setMaxBodySize(config.maxBodySize());
    ctx.setCompression(config.compression());
    try {
      handler.handle(ctx);
    } catch (Exception ex) {
      throw ex instanceof IOException io ? io : new IOException("Web request handler failed", ex);
    }
  }

  private void handleWebSocket(
      HttpServerExchange exchange, String correlationId, WebSocketMatch match, long maxMessageSize)
      throws Exception {
    WebSocketConnectionCallback callback =
        (wsExchange, channel) -> {
          UndertowWebSocketSession session =
              new UndertowWebSocketSession(
                  channel,
                  correlationId,
                  method(exchange),
                  path(exchange),
                  snapshotPathVariables(match.pathVariables()),
                  snapshotQueryParameters(exchange),
                  snapshotHeaders(exchange),
                  maxMessageSize);
          WebSocketListener listener;
          try {
            listener = match.endpoint().open(session);
          } catch (Exception ex) {
            throw new IllegalStateException("WebSocket endpoint failed", ex);
          }
          try {
            session.open(listener);
          } catch (Exception ex) {
            throw new IllegalStateException("WebSocket listener initialization failed", ex);
          }
        };
    WebSocketProtocolHandshakeHandler websocket = Handlers.websocket(callback);
    websocket.handleRequest(exchange);
  }

  private static boolean isWebSocketRequest(HttpServerExchange exchange) {
    String upgrade = exchange.getRequestHeaders().getFirst(Headers.UPGRADE);
    String connection = exchange.getRequestHeaders().getFirst(Headers.CONNECTION);
    return upgrade != null
        && "websocket".equalsIgnoreCase(upgrade)
        && connection != null
        && connection.toLowerCase(Locale.ROOT).contains("upgrade");
  }

  private static String method(HttpServerExchange exchange) {
    return exchange.getRequestMethod() != null ? exchange.getRequestMethod().toString() : "";
  }

  /**
   * Guarantees the echoed correlation id cannot inject response headers (defense in depth: HTTP/2
   * header values may legally contain CR/LF).
   */
  static String safeCorrelationId(String correlationId) {
    if (correlationId == null
        || correlationId.indexOf('\r') >= 0
        || correlationId.indexOf('\n') >= 0) {
      return UUID.randomUUID().toString().replace("-", "");
    }
    return correlationId;
  }

  private static String path(HttpServerExchange exchange) {
    String relative = exchange.getRelativePath();
    return relative != null ? relative : "/";
  }

  private static Map<String, String> snapshotPathVariables(Map<String, String> vars) {
    return vars == null ? Map.of() : Map.copyOf(vars);
  }

  private static Map<String, List<String>> snapshotQueryParameters(HttpServerExchange exchange) {
    LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
    for (Map.Entry<String, Deque<String>> entry : exchange.getQueryParameters().entrySet()) {
      params.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(params);
  }

  private static Map<String, List<String>> snapshotHeaders(HttpServerExchange exchange) {
    LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
    for (HttpString name : exchange.getRequestHeaders().getHeaderNames()) {
      List<String> values = new ArrayList<>();
      for (String value : exchange.getRequestHeaders().get(name)) {
        values.add(value);
      }
      headers.put(name.toString().toLowerCase(Locale.ROOT), List.copyOf(values));
    }
    return Map.copyOf(headers);
  }

  private record UndertowHandle(
      Undertow server,
      GracefulShutdownHandler gracefulShutdown,
      Duration shutdownGrace,
      String host)
      implements HttpServerHandle {
    @Override
    public int port() {
      return listenerPort(server);
    }

    @Override
    public void close() {
      try {
        gracefulShutdown.shutdown();
        gracefulShutdown.awaitShutdown(Math.max(0, shutdownGrace.toMillis()));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      } finally {
        server.stop();
        LOG.info("Freeway undertow web engine stopped");
      }
    }
  }

  private static int listenerPort(Undertow server) {
    if (server.getListenerInfo().isEmpty()) {
      return -1;
    }
    SocketAddress address = server.getListenerInfo().get(0).getAddress();
    if (address instanceof InetSocketAddress inetSocketAddress) {
      return inetSocketAddress.getPort();
    }
    return -1;
  }
}
