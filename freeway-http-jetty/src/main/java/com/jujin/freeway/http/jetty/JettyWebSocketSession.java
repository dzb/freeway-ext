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

import com.jujin.freeway.http.websocket.AbstractWebSocketSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Jetty-backed {@link WebSocketSession} with asynchronous frame sends. */
final class JettyWebSocketSession extends AbstractWebSocketSession {
  private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketSession.class);
  private static final Callback COMPLETION_CALLBACK =
      new Callback() {
        @Override
        public void succeed() {
          // Send scheduled; no-op on success.
        }

        @Override
        public void fail(Throwable failure) {
          LOG.warn("Jetty WebSocket send failed", failure);
        }
      };

  private final Session session;
  private final Object sendLock = new Object();
  private volatile boolean localCloseRequested;

  JettyWebSocketSession(
      Session session,
      String correlationId,
      String method,
      String path,
      Map<String, String> pathVariables,
      Map<String, List<String>> queryParams,
      Map<String, List<String>> headers) {
    this.session = Objects.requireNonNull(session, "session");
    super(correlationId, method, path, pathVariables, queryParams, headers);
  }

  @Override
  public boolean isOpen() {
    return session.isOpen();
  }

  @Override
  public void sendText(String text) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      // Async send: never block the receiving thread on network I/O.
      // Jetty serializes frames per session, so ordering is preserved.
      session.sendText(Objects.requireNonNull(text, "text"), completionCallback());
    }
  }

  @Override
  public void sendBinary(byte[] data) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      session.sendBinary(
          ByteBuffer.wrap(Objects.requireNonNull(data, "data")), completionCallback());
    }
  }

  @Override
  public void ping(byte[] data) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      session.sendPing(ByteBuffer.wrap(data != null ? data : new byte[0]), completionCallback());
    }
  }

  @Override
  public void flush() throws IOException {
    // Jetty sends frames immediately via the async send APIs.
  }

  @Override
  public void close(int code, String reason) throws IOException {
    localCloseRequested = true;
    // "Initiates a graceful close" per the interface contract — no join.
    session.close(code, closeReason(reason), completionCallback());
  }


  boolean localCloseRequested() {
    return localCloseRequested;
  }

  private void requireOpen() throws IOException {
    if (!session.isOpen()) {
      throw new IOException("WebSocket session is closed");
    }
  }

  private Callback completionCallback() {
    return COMPLETION_CALLBACK;
  }
}
