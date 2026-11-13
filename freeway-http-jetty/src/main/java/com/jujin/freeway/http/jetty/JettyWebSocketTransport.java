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

import com.jujin.freeway.http.websocket.WebSocketTransport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty's half of one WebSocket connection: frame sends and the local-close flag. The session
 * identity, metadata and close-reason truncation live in the composed {@code
 * WebSocketSessionSupport}; this class never sees them.
 */
final class JettyWebSocketTransport implements WebSocketTransport {
  private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketTransport.class);
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

  JettyWebSocketTransport(Session session) {
    this.session = Objects.requireNonNull(session, "session");
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
      session.sendText(Objects.requireNonNull(text, "text"), COMPLETION_CALLBACK);
    }
  }

  @Override
  public void sendBinary(byte[] data) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      session.sendBinary(
          ByteBuffer.wrap(Objects.requireNonNull(data, "data")), COMPLETION_CALLBACK);
    }
  }

  @Override
  public void ping(byte[] data) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      session.sendPing(ByteBuffer.wrap(data != null ? data : new byte[0]), COMPLETION_CALLBACK);
    }
  }

  /**
   * {@code reason} arrives already truncated to the 123-byte close-frame payload by {@code
   * WebSocketSessionSupport.close}; this method does not touch it again.
   */
  @Override
  public void close(int code, String reason) throws IOException {
    localCloseRequested = true;
    // "Initiates a graceful close" per the interface contract — no join.
    session.close(code, reason, COMPLETION_CALLBACK);
  }

  @Override
  public void flush() throws IOException {
    // Jetty sends frames immediately via the async send APIs.
  }

  boolean localCloseRequested() {
    return localCloseRequested;
  }

  private void requireOpen() throws IOException {
    if (!session.isOpen()) {
      throw new IOException("WebSocket session is closed");
    }
  }

  /** Batch sends through the same locked path, flushing once at the end. */
  @Override
  public void sendTextBatch(List<String> texts) throws IOException {
    for (String text : texts) {
      sendText(text);
    }
    flush();
  }
}
