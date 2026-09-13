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

import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketMatch;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket endpoint bridge between Jetty and the Freeway listener API.
 *
 * <p>Public on purpose: Jetty's {@code JettyWebSocketFrameHandlerFactory} resolves the listener's
 * lifecycle methods reflectively and fails with {@code IllegalAccessException} when the listener
 * class is not public, so a package-private bridge would break every upgrade at runtime.
 */
public final class JettyWebSocketBridge implements Session.Listener.AutoDemanding {
  private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketBridge.class);
  private final WebSocketMatch match;
  private final String correlationId;
  private final String method;
  private final String path;
  private final Map<String, String> pathVariables;
  private final Map<String, List<String>> queryParams;
  private final Map<String, List<String>> headers;
  private volatile WebSocketListener appListener = WebSocketListener.NOOP;
  private volatile JettyWebSocketSession session;

  JettyWebSocketBridge(
      WebSocketMatch match,
      String correlationId,
      String method,
      String path,
      Map<String, String> pathVariables,
      Map<String, List<String>> queryParams,
      Map<String, List<String>> headers) {
    this.match = Objects.requireNonNull(match, "match");
    // Null is fine: ExchangeMetaDefault auto-generates when blank.
    this.correlationId = correlationId;
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
            session, correlationId, method, path, pathVariables, queryParams, headers);
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
