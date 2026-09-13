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

import com.jujin.freeway.http.websocket.AbstractWebSocketSession;
import com.jujin.freeway.http.websocket.WebSocketListener;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;

/** Undertow-backed {@link WebSocketSession} with asynchronous frame sends. */
final class UndertowWebSocketSession extends AbstractWebSocketSession {
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
  private final long maxMessageSize;
  private final Object sendLock = new Object();
  private volatile boolean localCloseRequested;
  private volatile WebSocketListener listener = WebSocketListener.NOOP;

  UndertowWebSocketSession(
      WebSocketChannel channel,
      String correlationId,
      String method,
      String path,
      Map<String, String> pathVariables,
      Map<String, List<String>> queryParams,
      Map<String, List<String>> headers,
      long maxMessageSize) {
    this.channel = Objects.requireNonNull(channel, "channel");
    super(correlationId, method, path, pathVariables, queryParams, headers);
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
                // The cap is enforced while Undertow fills the buffer
                // (getMaxTextBufferSize below): an oversized message fails the
                // read with a 1009 close and never reaches this listener, so
                // there is nothing to re-check here.
                String text = message.getData();
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
                var pooled = message.getData();
                byte[] data;
                try {
                  ByteBuffer[] buffers = pooled.getResource();
                  long total = 0;
                  for (ByteBuffer buffer : buffers) {
                    total += buffer.remaining();
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
                var pooled = message.getData();
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
}
