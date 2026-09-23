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

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.ExchangeHandler;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.HttpServerHandle;
import com.jujin.freeway.http.MediaTypes;
import com.jujin.freeway.http.SslSettings;
import com.jujin.freeway.http.websocket.WebSocketMatch;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Jetty 12 transport adapter for the Freeway HTTP engine. */
public final class JettyHttpEngine implements HttpEngine {
  private static final Logger LOG = LoggerFactory.getLogger(JettyHttpEngine.class);
  private static final byte[] INTERNAL_ERROR_BODY =
      "Internal Server Error".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NOT_FOUND_BODY = "Not Found".getBytes(StandardCharsets.UTF_8);
  private static final byte[] UPGRADE_REJECTED_BODY =
      "WebSocket upgrade rejected".getBytes(StandardCharsets.UTF_8);
  private static final byte[] UPGRADE_FAILED_BODY =
      "WebSocket upgrade failed".getBytes(StandardCharsets.UTF_8);

  private final JsonCodec jsonCodec;
  private final Coercer coercer;
  private final SymbolSource symbols;
  private final ThreadLocal<JettyHttpContext> contextPool;

  public JettyHttpEngine(JsonCodec jsonCodec, Coercer coercer) {
    this(jsonCodec, coercer, SymbolSource.of(coercer, SymbolProvider.systemProperties()));
  }

  /**
   * Container path: {@code SymbolSource} is a container builtin, so composed use resolves every
   * knob below through the full cascade (CLI, JVM properties, env, files) instead of JVM properties
   * alone.
   */
  public JettyHttpEngine(JsonCodec jsonCodec, Coercer coercer, SymbolSource symbols) {
    this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    this.coercer = Objects.requireNonNull(coercer, "coercer");
    this.symbols = Objects.requireNonNull(symbols, "symbols");
    this.contextPool =
        ThreadLocal.withInitial(() -> new JettyHttpContext(this.jsonCodec, this.coercer));
  }

  /**
   * This adapter's own listener verdict: it resolves the shared {@code freeway.http.ssl.*} section
   * exactly as {@code buildConnector}/{@code start} does when it decides whether to install a TLS
   * listener, so the transport the engine serves and the {@link com.jujin.freeway.http.HttpServer}
   * reports are the same answer from the same rule.
   */
  @Override
  public boolean secure() {
    return SslSettings.from(symbols).enabled();
  }

  @Override
  public HttpServerHandle start(HttpServerConfig config, ExchangeHandler handler)
      throws IOException {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(handler, "handler");

    Server server = new Server();
    ServerConnector connector = buildConnector(server, config);
    connector.setHost(config.host());
    connector.setPort(config.port());
    connector.setAcceptQueueSize(config.backlog());
    connector.setIdleTimeout(config.readTimeout().toMillis());
    if (config.receiveBufferSize() > 0) {
      connector.setAcceptedReceiveBufferSize(config.receiveBufferSize());
    }
    if (config.sendBufferSize() > 0) {
      connector.setAcceptedSendBufferSize(config.sendBufferSize());
    }
    server.addConnector(connector);
    server.setStopTimeout(config.shutdownGrace().toMillis());
    if (config.maxConnections() > 0) {
      // Rejects excess connections at accept time (Jetty 12's non-deprecated
      // connection-limit listener), matching the built-in engine's
      // max-connections semantics.
      server.addBean(new NetworkConnectionLimit(config.maxConnections(), server));
    }
    // Honor contract: a field Jetty cannot apply says so at startup rather
    // than looking applied.
    if (!HttpServerConfig.DEFAULT_WRITE_TIMEOUT.equals(config.writeTimeout())) {
      LOG.warn(
          "{}={} is not applied: Jetty has no write-timeout counterpart",
          HttpConfigKeys.SERVER_WRITE_TIMEOUT,
          config.writeTimeout());
    }

    ServerWebSocketContainer webSocketContainer = ServerWebSocketContainer.ensure(server);
    long maxFrameSize =
        parseMaxFrameSize(symbols.resolve("freeway.http.websocket.max-frame-size", "65536"));
    if (maxFrameSize > 0) {
      webSocketContainer.setMaxTextMessageSize(maxFrameSize);
      webSocketContainer.setMaxBinaryMessageSize(maxFrameSize);
    } else {
      // 0 (or negative) disables the message-size limit per the documented
      // property semantics; otherwise Jetty's 64 KiB default would remain.
      webSocketContainer.setMaxTextMessageSize(Long.MAX_VALUE);
      webSocketContainer.setMaxBinaryMessageSize(Long.MAX_VALUE);
    }
    GracefulHandler graceful = new GracefulHandler();
    // A BLOCKING-declared handler is executed by the connection's producer thread through the
    // pool's per-request hand-off; a NON_BLOCKING one runs inline on the producer instead.
    // Freeway handlers are application code that may block (body reads, DB access, downstream
    // calls), so pool dispatch stays the default; freeway.http.jetty.dispatch-io=false opts a
    // fully non-blocking application out of the hand-off (the benchmark measured ~1.6x on
    // keep-alive ping for the same shape). If such a handler blocks anyway it stalls the shared
    // producer/selector thread — the same documented danger as the Undertow adapter's
    // freeway.http.undertow.dispatch-io=false.
    graceful.setHandler(
        new Handler.Abstract(
            invocationType(symbols.resolve("freeway.http.jetty.dispatch-io", "true"))) {
          @Override
          public boolean handle(Request request, Response response, Callback callback)
              throws Exception {
            String correlationId = request.getHeaders().get("X-Request-Id");
            response.getHeaders().put("X-Request-Id", safeCorrelationId(correlationId));
            if (isWebSocketRequest(request)) {
              return handleWebSocket(
                  request, response, callback, handler, correlationId, webSocketContainer);
            }
            JettyHttpContext ctx = contextPool.get();
            ctx.reset(request, response, correlationId, callback);
            ctx.setMaxBodySize(config.maxBodySize());
            ctx.setCompression(config.compression());
            try {
              handler.handle(ctx);
            } catch (Exception ex) {
              LOG.error("Jetty request failed for {} {}", method(request), path(request), ex);
              if (!response.isCommitted()) {
                response.setStatus(500);
                response.getHeaders().put("Content-Type", MediaTypes.TEXT_PLAIN_UTF8);
                response.write(true, ByteBuffer.wrap(INTERNAL_ERROR_BODY), callback);
              } else {
                // Response was already committed by the handler before it
                // failed; writing a 500 now would throw. Just end the exchange.
                callback.succeeded();
              }
              return true;
            }
            if (!ctx.responded()) {
              callback.succeeded();
            }
            return true;
          }
        });
    server.setHandler(graceful);
    try {
      server.start();
    } catch (Exception ex) {
      throw new IOException("Unable to start Jetty server", ex);
    }

    int port = currentPort(server);
    LOG.info("Freeway jetty web engine started on {}:{}", config.host(), port);
    return new JettyHandle(server, graceful, config.shutdownGrace(), config.host(), port);
  }

  /**
   * Maps the {@code freeway.http.jetty.dispatch-io} knob to the root handler's invocation type:
   * only {@code false} opts out of pool dispatch (NON_BLOCKING); anything else, including a
   * malformed value, keeps the safe BLOCKING default — mirroring how {@code
   * freeway.http.undertow.dispatch-io} treats unknown values.
   */
  static Invocable.InvocationType invocationType(String dispatchIo) {
    return "false".equalsIgnoreCase(dispatchIo)
        ? Invocable.InvocationType.NON_BLOCKING
        : Invocable.InvocationType.BLOCKING;
  }

  /**
   * Builds the listener stack from the shared {@code freeway.http.ssl.*} keys, matching the
   * built-in engine: {@code freeway.http.ssl.enabled} + key-store / key-store-password /
   * key-store-type / trust-store / client-auth / protocols / ciphers, and {@code
   * freeway.http.ssl.http2} (default true) for h2 via ALPN when TLS is enabled. The Jetty-only
   * {@code freeway.http.http2} toggle remains for h2c (cleartext HTTP/2); it is ignored when TLS is
   * enabled.
   */
  private ServerConnector buildConnector(Server server, HttpServerConfig config) {
    // The shared TLS section, resolved once by core: the same keys, defaults
    // and three-state activation the built-in engine and Undertow use, so the
    // HttpServer's secure() verdict and this adapter cannot disagree.
    SslSettings tls = SslSettings.from(symbols);
    boolean sslEnabled = tls.enabled();
    boolean alpnHttp2 = sslEnabled && tls.http2();
    boolean h2c =
        !sslEnabled && Boolean.parseBoolean(symbols.resolve("freeway.http.http2", "false"));
    if (!sslEnabled && !h2c) {
      return new ServerConnector(server);
    }
    if (sslEnabled) {
      SslContextFactory.Server ssl = new SslContextFactory.Server();
      ssl.setKeyStorePath(tls.keyStorePath());
      ssl.setKeyStorePassword(tls.keyStorePassword());
      ssl.setKeyStoreType(tls.keyStoreType());
      if (tls.trustStorePath() != null) {
        ssl.setTrustStorePath(tls.trustStorePath());
        ssl.setTrustStorePassword(tls.trustStorePassword());
        ssl.setTrustStoreType(tls.trustStoreType());
      }
      if (tls.clientAuth()) {
        ssl.setNeedClientAuth(true);
      }
      if (tls.protocols() != null && !tls.protocols().isEmpty()) {
        ssl.setIncludeProtocols(tls.protocols().toArray(String[]::new));
      }
      if (tls.ciphers() != null && !tls.ciphers().isEmpty()) {
        ssl.setIncludeCipherSuites(tls.ciphers().toArray(String[]::new));
      }
      // Jetty extensions kept from the pre-refactor adapter: separate key
      // manager password and certificate alias selection.
      String keyPassword = symbols.resolve("freeway.http.ssl.key-password", null);
      if (keyPassword != null) {
        ssl.setKeyManagerPassword(keyPassword);
      }
      String alias = symbols.resolve("freeway.http.ssl.key-alias", null);
      if (alias != null) {
        ssl.setCertAlias(alias);
      }
      HttpConfiguration https = new HttpConfiguration();
      https.addCustomizer(new SecureRequestCustomizer());
      HttpConnectionFactory http11 = new HttpConnectionFactory(https);
      if (alpnHttp2) {
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(https);
        // Route the SSL connection through the ALPN factory (its "alpn"
        // protocol name), otherwise the TLS handshake skips ALPN entirely
        // and clients silently fall back to HTTP/1.1.
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2", "http/1.1");
        alpn.setDefaultProtocol(http11.getProtocol());
        return new ServerConnector(
            server, new SslConnectionFactory(ssl, alpn.getProtocol()), alpn, h2, http11);
      }
      return new ServerConnector(
          server, new SslConnectionFactory(ssl, http11.getProtocol()), http11);
    }
    // h2c: HTTP/2 over cleartext.
    HttpConfiguration http = new HttpConfiguration();
    return new ServerConnector(
        server, new HttpConnectionFactory(http), new HTTP2CServerConnectionFactory(http));
  }

  /** Splits a comma-separated property value into trimmed, non-empty tokens. */
  private static String[] splitCommaSeparated(String value) {
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
  }

  /**
   * Parses the shared WebSocket max-frame-size knob. Malformed values fail startup naming the key
   * instead of silently falling back the way {@code Long.getLong} did.
   */
  private static long parseMaxFrameSize(String raw) {
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException | NullPointerException ex) {
      throw new IllegalArgumentException(
          "freeway.http.websocket.max-frame-size must be a byte count, got: '" + raw + "'", ex);
    }
  }

  private boolean handleWebSocket(
      Request request,
      Response response,
      Callback callback,
      ExchangeHandler handler,
      String correlationId,
      ServerWebSocketContainer webSocketContainer) {
    String method = method(request);
    String path = path(request);
    String origin = request.getHeaders().get("Origin");
    WebSocketMatch match = handler.websocket(method, path, origin);
    if (match == null) {
      response.setStatus(404);
      response.getHeaders().put("Content-Type", MediaTypes.TEXT_PLAIN_UTF8);
      response.write(true, ByteBuffer.wrap(NOT_FOUND_BODY), callback);
      return true;
    }
    WebSocketCreator creator =
        (upgradeRequest, upgradeResponse, upgradeCallback) -> {
          upgradeResponse.getHeaders().put("X-Request-Id", safeCorrelationId(correlationId));
          return new JettyWebSocketBridge(
              match,
              correlationId,
              method,
              path,
              snapshotPathVariables(match.pathVariables()),
              snapshotQueryParameters(upgradeRequest),
              snapshotHeaders(upgradeRequest));
        };
    try {
      if (!webSocketContainer.upgrade(creator, request, response, callback)) {
        response.setStatus(400);
        response.getHeaders().put("Content-Type", MediaTypes.TEXT_PLAIN_UTF8);
        response.write(true, ByteBuffer.wrap(UPGRADE_REJECTED_BODY), callback);
      }
    } catch (Exception ex) {
      LOG.warn("Jetty websocket upgrade failed for {} {}", method, path, ex);
      if (!response.isCommitted()) {
        response.setStatus(500);
        response.getHeaders().put("Content-Type", MediaTypes.TEXT_PLAIN_UTF8);
        response.write(true, ByteBuffer.wrap(UPGRADE_FAILED_BODY), callback);
      } else {
        callback.succeeded();
      }
    }
    return true;
  }

  private static boolean isWebSocketRequest(Request request) {
    String upgrade = request.getHeaders().get("Upgrade");
    String connection = request.getHeaders().get("Connection");
    return upgrade != null
        && "websocket".equalsIgnoreCase(upgrade)
        && connection != null
        && connection.toLowerCase(Locale.ROOT).contains("upgrade");
  }

  private static String method(Request request) {
    String method = request.getMethod();
    return method != null ? method : "";
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

  private static String path(Request request) {
    String path = request.getHttpURI() != null ? request.getHttpURI().getPath() : null;
    return path != null ? path : "/";
  }

  private static Map<String, String> snapshotPathVariables(Map<String, String> vars) {
    return vars == null ? Map.of() : Map.copyOf(vars);
  }

  private static Map<String, List<String>> snapshotQueryParameters(Request request) {
    Fields fields = Request.extractQueryParameters(request);
    LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
    for (Fields.Field field : fields) {
      params.put(field.getName(), List.copyOf(field.getValues()));
    }
    return Map.copyOf(params);
  }

  private static Map<String, List<String>> snapshotHeaders(Request request) {
    LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
    for (String name : request.getHeaders().getFieldNamesCollection()) {
      List<String> values = new ArrayList<>(request.getHeaders().getValuesList(name));
      headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values));
    }
    return Map.copyOf(headers);
  }

  private static int currentPort(Server server) {
    for (Connector connector : server.getConnectors()) {
      if (connector instanceof ServerConnector serverConnector) {
        int port = serverConnector.getLocalPort();
        if (port > 0) {
          return port;
        }
      }
    }
    return -1;
  }

  private record JettyHandle(
      Server server, GracefulHandler graceful, Duration shutdownGrace, String host, int port)
      implements HttpServerHandle {
    @Override
    public int port() {
      return port;
    }

    @Override
    public void close() {
      try {
        graceful.shutdown().get(Math.max(0, shutdownGrace.toMillis()), TimeUnit.MILLISECONDS);
      } catch (InterruptedException ex) {
        // The caller asked us to stop waiting; the interruption must stay
        // visible after this method returns (Undertow's close does the same).
        Thread.currentThread().interrupt();
      } catch (Exception ex) {
        // fall through to stop
      } finally {
        try {
          server.stop();
        } catch (Exception ex) {
          throw new IllegalStateException("Failed to stop Jetty server", ex);
        }
        LOG.info("Freeway jetty web engine stopped");
      }
    }
  }
}
