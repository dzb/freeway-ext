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

import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketSession;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.Pooled;

/** Undertow-backed {@link WebSocketSession} with asynchronous frame sends. */
final class UndertowWebSocketSession implements WebSocketSession {
  private static final Logger LOG = LoggerFactory.getLogger(UndertowWebSocketSession.class);
  private static final WebSocketCallback<Void> SEND_CALLBACK =
      new WebSocketCallback<>() {
        @Override
        public void complete(WebSocketChannel channel, Void context) {
          // Sent; nothing to do.
        }

        @Override
        public void onError(WebSocketChannel channel, Void context, Throwable throwable) {
          LOG.warn("Undertow WebSocket send failed", throwable);
        }
      };

  private final WebSocketChannel channel;
  private final RequestContext requestContext;
  private final String method;
  private final String path;
  private final Map<String, String> pathVariables;
  private final Map<String, List<String>> queryParams;
  private final Map<String, List<String>> headers;
  private final long maxMessageSize;
  private final Object sendLock = new Object();
  private volatile boolean localCloseRequested;
  private volatile WebSocketListener listener = WebSocketListener.NOOP;

  UndertowWebSocketSession(
      WebSocketChannel channel,
      RequestContext requestContext,
      String method,
      String path,
      Map<String, String> pathVariables,
      Map<String, List<String>> queryParams,
      Map<String, List<String>> headers,
      long maxMessageSize) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
    this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
    this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    this.maxMessageSize = maxMessageSize;
  }

  void open(WebSocketListener listener) throws Exception {
    this.listener = listener != null ? listener : WebSocketListener.NOOP;
    this.listener.onOpen(this);
    channel
        .getReceiveSetter()
        .set(
            new AbstractReceiveListener() {
              @Override
              protected long getMaxTextBufferSize() {
                // Undertow's default is -1 (unlimited); enforce the configured
                // cap, and pass -1 through when the limit is disabled.
                return maxMessageSize > 0 ? maxMessageSize : -1;
              }

              @Override
              protected long getMaxBinaryBufferSize() {
                return maxMessageSize > 0 ? maxMessageSize : -1;
              }

              @Override
              protected void onFullTextMessage(
                  WebSocketChannel channel, BufferedTextMessage message) throws IOException {
                String text = message.getData();
                if (maxMessageSize > 0) {
                  int bytes = text.getBytes(StandardCharsets.UTF_8).length;
                  if (bytes > maxMessageSize) {
                    rejectOversized(channel, bytes);
                  }
                }
                try {
                  UndertowWebSocketSession.this.listener.onText(text);
                } catch (Exception ex) {
                  fail(channel, ex);
                }
              }

              @Override
              protected void onFullBinaryMessage(
                  WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                // getData() transfers ownership of the pooled buffers; the
                // default listener frees them in a finally, and so must we.
                Pooled<ByteBuffer[]> pooled = message.getData();
                byte[] data;
                try {
                  ByteBuffer[] buffers = pooled.getResource();
                  long total = 0;
                  for (ByteBuffer buffer : buffers) {
                    total += buffer.remaining();
                  }
                  if (maxMessageSize > 0 && total > maxMessageSize) {
                    rejectOversized(channel, total);
                  }
                  ByteBuffer merged = WebSockets.mergeBuffers(buffers);
                  data = new byte[merged.remaining()];
                  merged.get(data);
                } finally {
                  pooled.free();
                }
                try {
                  UndertowWebSocketSession.this.listener.onBinary(data);
                } catch (Exception ex) {
                  fail(channel, ex);
                }
              }

              @Override
              protected void onFullCloseMessage(
                  WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                Pooled<ByteBuffer[]> pooled = message.getData();
                CloseMessage closeMessage;
                try {
                  closeMessage = new CloseMessage(WebSockets.mergeBuffers(pooled.getResource()));
                } finally {
                  pooled.free();
                }
                try {
                  UndertowWebSocketSession.this.listener.onClose(
                      closeMessage.getCode(), closeMessage.getReason(), !localCloseRequested);
                  if (!channel.isCloseFrameSent()) {
                    WebSockets.sendClose(closeMessage, channel, SEND_CALLBACK);
                  }
                } catch (Exception ex) {
                  fail(channel, ex);
                }
              }

              @Override
              protected void onError(WebSocketChannel channel, Throwable error) {
                try {
                  UndertowWebSocketSession.this.listener.onError(error);
                } catch (Exception ignored) {
                }
                // Undertow's default onError closes the channel; without it a
                // read-level error leaves the connection hanging with onClose
                // never delivered to the application.
                IoUtils.safeClose(channel);
              }
            });
    channel.resumeReceives();
  }

  @Override
  public String method() {
    return method;
  }

  @Override
  public String path() {
    return path;
  }

  @Override
  public String pathVar(String name) {
    return pathVariables.get(name);
  }

  @Override
  public Map<String, String> pathVars() {
    return pathVariables;
  }

  @Override
  public String queryParam(String name) {
    List<String> values = queryParams.get(name);
    return values != null && !values.isEmpty() ? values.get(0) : null;
  }

  @Override
  public List<String> queryParams(String name) {
    return queryParams.getOrDefault(name, List.of());
  }

  @Override
  public Map<String, List<String>> queryParams() {
    return queryParams;
  }

  @Override
  public String header(String name) {
    List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
    return values != null && !values.isEmpty() ? values.get(0) : null;
  }

  @Override
  public List<String> headers(String name) {
    return headers.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), List.of());
  }

  @Override
  public RequestContext requestContext() {
    return requestContext;
  }

  @Override
  public boolean isOpen() {
    return channel.isOpen();
  }

  @Override
  public void sendText(String text) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      // Async send: receive callbacks run on XNIO I/O threads, where
      // blocking sends must never be used. Frames are queued per channel;
      // failures are logged via SEND_CALLBACK (never thrown to the caller).
      WebSockets.sendText(Objects.requireNonNull(text, "text"), channel, SEND_CALLBACK);
    }
  }

  @Override
  public void sendBinary(byte[] data) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      WebSockets.sendBinary(
          ByteBuffer.wrap(Objects.requireNonNull(data, "data")), channel, SEND_CALLBACK);
    }
  }

  @Override
  public void ping(byte[] data) throws IOException {
    synchronized (sendLock) {
      requireOpen();
      WebSockets.sendPing(
          ByteBuffer.wrap(data != null ? data : new byte[0]), channel, SEND_CALLBACK);
    }
  }

  @Override
  public void close(int code, String reason) throws IOException {
    localCloseRequested = true;
    // "Initiates a graceful close" per the interface contract — no blocking.
    WebSockets.sendClose(code, closeReason(reason), channel, SEND_CALLBACK);
  }

  /** RFC 6455 caps close-frame payloads at 125 bytes (reason <= 123 bytes). */
  static String closeReason(String reason) {
    if (reason == null) return "";
    byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= 123) return reason;
    // May split a multi-byte character; a replacement char in the reason is
    // acceptable for an informational payload.
    return new String(bytes, 0, 123, StandardCharsets.UTF_8);
  }

  @Override
  public void flush() throws IOException {
    // Undertow's WebSocket sends frames immediately, no buffering needed
  }

  private void fail(WebSocketChannel channel, Throwable cause) throws IOException {
    try {
      listener.onError(cause);
    } catch (Exception ignored) {
    }
    try {
      close(
          1011,
          cause != null && cause.getMessage() != null ? cause.getMessage() : "websocket error");
    } catch (IOException ex) {
      IoUtils.safeClose(channel);
      throw ex;
    }
  }

  private void requireOpen() throws IOException {
    if (!channel.isOpen()) {
      throw new IOException("WebSocket channel is closed");
    }
  }

  /**
   * Rejects a message that exceeds the configured limit: sends a 1009 close frame (matching the
   * Jetty adapter) and fails the receive so the channel breaks. Note: Undertow 2.4 buffers the full
   * message before the receive listeners run, so the cap cannot bound the transient buffering
   * itself; it guarantees the message never reaches application code.
   */
  private void rejectOversized(WebSocketChannel channel, long actual) throws IOException {
    WebSockets.sendClose(
        new CloseMessage(1009, "Message size exceeds limit " + maxMessageSize),
        channel,
        SEND_CALLBACK);
    throw new IOException(
        "Message of " + actual + " bytes exceeds WebSocket limit " + maxMessageSize);
  }
}
