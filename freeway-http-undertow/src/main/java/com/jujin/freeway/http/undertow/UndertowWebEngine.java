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
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Undertow transport adapter for the Freeway HTTP engine. */
public final class UndertowWebEngine implements HttpEngine {
  private static final Logger LOG = LoggerFactory.getLogger(UndertowWebEngine.class);
  private static final HttpString X_REQUEST_ID = new HttpString("X-Request-Id");
  private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";
  private static final String INTERNAL_ERROR_BODY = "Internal Server Error";

  private final JsonCodec jsonCodec;
  private final Coercer coercer;
  private final ThreadLocal<UndertowHttpContext> contextPool;

  public UndertowWebEngine(JsonCodec jsonCodec, Coercer coercer) {
    this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    this.coercer = Objects.requireNonNull(coercer, "coercer");
    this.contextPool =
        ThreadLocal.withInitial(() -> new UndertowHttpContext(this.jsonCodec, this.coercer));
  }

  @Override
  public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler) {
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
    HttpHandler root =
        exchange -> {
          if (dispatchIo && exchange.isInIoThread()) {
            exchange.dispatch(() -> handle(exchange, handler, config));
          } else {
            handle(exchange, handler, config);
          }
        };
    GracefulShutdownHandler gracefulShutdown = Handlers.gracefulShutdown(root);
    Undertow.Builder builder =
        Undertow.builder()
            .setHandler(gracefulShutdown)
            .setIoThreads(Runtime.getRuntime().availableProcessors());
    // Undertow defaults to workerThreads = ioThreads * 8; the old explicit
    // 1-thread worker pool starved blocking handlers.
    if (Boolean.getBoolean("freeway.http.ssl.enabled")) {
      builder.addHttpsListener(config.port(), config.host(), sslContext());
    } else {
      builder.addHttpListener(config.port(), config.host());
    }
    Undertow server = builder.build();
    server.start();
    LOG.info("Freeway undertow web engine started on {}:{}", config.host(), listenerPort(server));
    return new UndertowHandle(server, gracefulShutdown, config.shutdownGrace(), config.host());
  }

  /**
   * Builds a TLS context from {@code freeway.http.ssl.key-store} and {@code
   * freeway.http.ssl.key-store-password} (JKS vs PKCS12 is inferred from the file extension).
   */
  private static SSLContext sslContext() {
    String keyStorePath = System.getProperty("freeway.http.ssl.key-store");
    char[] password = System.getProperty("freeway.http.ssl.key-store-password", "").toCharArray();
    try {
      String type =
          keyStorePath != null && keyStorePath.toLowerCase(Locale.ROOT).endsWith(".jks")
              ? "JKS"
              : "PKCS12";
      try (var in = Files.newInputStream(Path.of(keyStorePath))) {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(in, password);
        KeyManagerFactory kmf =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to configure TLS for Undertow", ex);
    }
  }

  private void handle(
      HttpServerExchange exchange, HttpRequestHandler handler, HttpServerConfig config) {
    try {
      dispatch(exchange, handler, config);
    } catch (Exception ex) {
      LOG.error("Undertow request failed for {} {}", method(exchange), path(exchange), ex);
      if (!exchange.isResponseStarted()) {
        exchange.setStatusCode(500);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN_UTF8);
        exchange.getResponseSender().send(INTERNAL_ERROR_BODY);
      } else {
        // Response was already started by the handler before it failed;
        // writing a 500 now would throw. Just end the exchange.
        exchange.endExchange();
      }
    }
  }

  private void dispatch(
      HttpServerExchange exchange, HttpRequestHandler handler, HttpServerConfig config)
      throws Exception {
    RequestContext requestContext =
        HttpContext.createRequestContext(exchange.getRequestHeaders().getFirst("X-Request-Id"));
    exchange
        .getResponseHeaders()
        .put(X_REQUEST_ID, safeCorrelationId(requestContext.correlationId()));
    if (isWebSocketRequest(exchange)) {
      String origin = exchange.getRequestHeaders().getFirst(Headers.ORIGIN);
      WebSocketMatch match = handler.websocket(method(exchange), path(exchange), origin);
      if (match == null) {
        ResponseCodeHandler.HANDLE_404.handleRequest(exchange);
        return;
      }
      handleWebSocket(exchange, requestContext, match);
      return;
    }

    UndertowHttpContext ctx = contextPool.get();
    ctx.reset(exchange, requestContext);
    ctx.maxBodySize(config.maxBodySize());
    try {
      handler.handle(ctx);
    } catch (Exception ex) {
      throw ex instanceof IOException io ? io : new IOException("Web request handler failed", ex);
    }
  }

  private void handleWebSocket(
      HttpServerExchange exchange, RequestContext requestContext, WebSocketMatch match)
      throws Exception {
    WebSocketConnectionCallback callback =
        (wsExchange, channel) -> {
          UndertowWebSocketSession session =
              new UndertowWebSocketSession(
                  channel,
                  requestContext,
                  method(exchange),
                  path(exchange),
                  snapshotPathVariables(match.pathVariables()),
                  snapshotQueryParameters(exchange),
                  snapshotHeaders(exchange));
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
      headers.put(name.toString().toLowerCase(java.util.Locale.ROOT), List.copyOf(values));
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
