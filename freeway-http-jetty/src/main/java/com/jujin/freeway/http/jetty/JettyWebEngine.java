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
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.websocket.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Jetty 12 transport adapter for the Freeway HTTP engine. */
public final class JettyWebEngine implements HttpEngine {
  private static final Logger LOG = LoggerFactory.getLogger(JettyWebEngine.class);
  private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";
  private static final byte[] INTERNAL_ERROR_BODY =
      "Internal Server Error".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NOT_FOUND_BODY = "Not Found".getBytes(StandardCharsets.UTF_8);
  private static final byte[] UPGRADE_REJECTED_BODY =
      "WebSocket upgrade rejected".getBytes(StandardCharsets.UTF_8);
  private static final byte[] UPGRADE_FAILED_BODY =
      "WebSocket upgrade failed".getBytes(StandardCharsets.UTF_8);

  private final JsonCodec jsonCodec;
  private final Coercer coercer;
  private final ThreadLocal<JettyHttpContext> contextPool;

  public JettyWebEngine(JsonCodec jsonCodec, Coercer coercer) {
    this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    this.coercer = Objects.requireNonNull(coercer, "coercer");
    this.contextPool =
        ThreadLocal.withInitial(() -> new JettyHttpContext(this.jsonCodec, this.coercer));
  }

  @Override
  public HttpServerHandle start(HttpServerConfig config, HttpRequestHandler handler)
      throws IOException {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(handler, "handler");

    Server server = new Server();
    ServerConnector connector = buildConnector(server);
    connector.setHost(config.host());
    connector.setPort(config.port());
    connector.setAcceptQueueSize(config.backlog());
    server.addConnector(connector);
    server.setStopTimeout(config.shutdownGrace().toMillis());

    ServerWebSocketContainer webSocketContainer = ServerWebSocketContainer.ensure(server);
    long maxFrameSize = Long.getLong("freeway.http.websocket.max-frame-size", 65_536L);
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
    graceful.setHandler(
        new Handler.Abstract() {
          @Override
          public boolean handle(
              Request request, Response response, org.eclipse.jetty.util.Callback callback)
              throws Exception {
            RequestContext requestContext =
                HttpContext.createRequestContext(request.getHeaders().get("X-Request-Id"));
            response
                .getHeaders()
                .put("X-Request-Id", safeCorrelationId(requestContext.correlationId()));
            if (isWebSocketRequest(request)) {
              return handleWebSocket(
                  request, response, callback, handler, requestContext, webSocketContainer);
            }
            JettyHttpContext ctx = contextPool.get();
            ctx.reset(request, response, requestContext, callback);
            ctx.maxBodySize(config.maxBodySize());
            try {
              handler.handle(ctx);
            } catch (Exception ex) {
              LOG.error("Jetty request failed for {} {}", method(request), path(request), ex);
              if (!response.isCommitted()) {
                response.setStatus(500);
                response.getHeaders().put("Content-Type", TEXT_PLAIN_UTF8);
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
   * Builds the listener stack from system properties: {@code freeway.http.ssl.enabled} (+ key-store
   * / key-store-password / key-password / key-alias) and {@code freeway.http.http2} (h2c or h2 via
   * ALPN when TLS is enabled).
   */
  private static ServerConnector buildConnector(Server server) {
    boolean sslEnabled = Boolean.getBoolean("freeway.http.ssl.enabled");
    boolean http2 = Boolean.getBoolean("freeway.http.http2");
    if (!sslEnabled && !http2) {
      return new ServerConnector(server);
    }
    if (sslEnabled) {
      SslContextFactory.Server ssl = new SslContextFactory.Server();
      ssl.setKeyStorePath(System.getProperty("freeway.http.ssl.key-store"));
      ssl.setKeyStorePassword(System.getProperty("freeway.http.ssl.key-store-password", ""));
      String keyPassword = System.getProperty("freeway.http.ssl.key-password");
      if (keyPassword != null) {
        ssl.setKeyManagerPassword(keyPassword);
      }
      String alias = System.getProperty("freeway.http.ssl.key-alias");
      if (alias != null) {
        ssl.setCertAlias(alias);
      }
      HttpConfiguration https = new HttpConfiguration();
      https.addCustomizer(new SecureRequestCustomizer());
      HttpConnectionFactory http11 = new HttpConnectionFactory(https);
      if (http2) {
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

  private boolean handleWebSocket(
      Request request,
      Response response,
      org.eclipse.jetty.util.Callback callback,
      HttpRequestHandler handler,
      RequestContext requestContext,
      ServerWebSocketContainer webSocketContainer) {
    String method = method(request);
    String path = path(request);
    String origin = request.getHeaders().get("Origin");
    WebSocketMatch match = handler.websocket(method, path, origin);
    if (match == null) {
      response.setStatus(404);
      response.getHeaders().put("Content-Type", TEXT_PLAIN_UTF8);
      response.write(true, ByteBuffer.wrap(NOT_FOUND_BODY), callback);
      return true;
    }
    WebSocketCreator creator =
        (upgradeRequest, upgradeResponse, upgradeCallback) -> {
          upgradeResponse
              .getHeaders()
              .put("X-Request-Id", safeCorrelationId(requestContext.correlationId()));
          return new JettyWebSocketBridge(
              match,
              requestContext,
              method,
              path,
              snapshotPathVariables(match.pathVariables()),
              snapshotQueryParameters(upgradeRequest),
              snapshotHeaders(upgradeRequest));
        };
    try {
      if (!webSocketContainer.upgrade(creator, request, response, callback)) {
        response.setStatus(400);
        response.getHeaders().put("Content-Type", TEXT_PLAIN_UTF8);
        response.write(true, ByteBuffer.wrap(UPGRADE_REJECTED_BODY), callback);
      }
    } catch (Exception ex) {
      LOG.warn("Jetty websocket upgrade failed for {} {}", method, path, ex);
      if (!response.isCommitted()) {
        response.setStatus(500);
        response.getHeaders().put("Content-Type", TEXT_PLAIN_UTF8);
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
      headers.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(values));
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

  /** WebSocket endpoint bridge between Jetty and the Freeway listener API. */
  public static final class JettyWebSocketBridge implements Session.Listener.AutoDemanding {
    private final WebSocketMatch match;
    private final RequestContext requestContext;
    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> headers;
    private volatile WebSocketListener appListener = WebSocketListener.NOOP;
    private volatile JettyWebSocketSession session;

    JettyWebSocketBridge(
        WebSocketMatch match,
        RequestContext requestContext,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers) {
      this.match = Objects.requireNonNull(match, "match");
      this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
      this.method = Objects.requireNonNull(method, "method");
      this.path = Objects.requireNonNull(path, "path");
      this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
      this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
      this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public void onWebSocketOpen(Session session) {
      JettyWebSocketSession wsSession =
          new JettyWebSocketSession(
              session, requestContext, method, path, pathVariables, queryParams, headers);
      this.session = wsSession;
      try {
        appListener = match.endpoint().open(wsSession);
        if (appListener == null) {
          appListener = WebSocketListener.NOOP;
        }
        appListener.onOpen(wsSession);
      } catch (Exception ex) {
        throw new IllegalStateException("WebSocket endpoint failed", ex);
      }
    }

    @Override
    public void onWebSocketText(String message) {
      try {
        appListener.onText(message);
      } catch (Exception ex) {
        onWebSocketError(ex);
        closeWithError(ex);
      }
    }

    @Override
    public void onWebSocketBinary(
        ByteBuffer payload, org.eclipse.jetty.websocket.api.Callback callback) {
      try {
        byte[] data;
        if (payload == null) {
          data = new byte[0];
        } else {
          ByteBuffer copy = payload.slice();
          data = new byte[copy.remaining()];
          copy.get(data);
        }
        appListener.onBinary(data);
        callback.succeed();
      } catch (Exception ex) {
        onWebSocketError(ex);
        callback.fail(ex);
      }
    }

    @Override
    public void onWebSocketClose(
        int statusCode, String reason, org.eclipse.jetty.websocket.api.Callback callback) {
      try {
        appListener.onClose(statusCode, reason, session != null && !session.localCloseRequested());
        callback.succeed();
      } catch (Exception ex) {
        callback.fail(ex);
      }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
      try {
        appListener.onError(cause);
      } catch (Exception ex) {
        LOG.warn("Jetty websocket listener failed while handling error", ex);
      }
    }

    private void closeWithError(Throwable cause) {
      if (session == null || !session.isOpen()) {
        return;
      }
      try {
        session.close(
            1011,
            cause != null && cause.getMessage() != null ? cause.getMessage() : "websocket error");
      } catch (Exception ex) {
        LOG.warn("Jetty websocket session failed to close after error", ex);
      }
    }
  }
}
