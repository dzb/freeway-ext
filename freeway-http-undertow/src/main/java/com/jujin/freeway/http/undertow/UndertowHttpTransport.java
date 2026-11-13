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

import com.jujin.freeway.http.Compression;
import com.jujin.freeway.http.HttpTransport;
import com.jujin.freeway.http.ResponseBody;
import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.ResponsePlan;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.sse.SseEmitter;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RequestTooBigException;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Undertow's half of one exchange: it reads the native request facts and writes the planned
 * response. Everything shared — status and header validation, {@code Date}, {@code Content-Length},
 * the gzip decision, {@code Vary}, the body limit — lives in the {@code HttpExchangeSupport}
 * composed over this object. One transport per exchange; nothing is pooled and there is no reset.
 */
final class UndertowHttpTransport implements HttpTransport {

  private final HttpServerExchange exchange;
  private final long maxBodySize;

  private String method;
  private String path;
  private Map<String, List<String>> queryParams;
  private Map<String, List<String>> requestHeaders;
  private volatile boolean committed;

  /**
   * @param exchange the native exchange this transport wraps
   * @param maxBodySize the body ceiling Undertow was configured with, reported when the native
   *     limit trips before the framework's limiter sees the bytes
   */
  UndertowHttpTransport(HttpServerExchange exchange, long maxBodySize) {
    this.exchange = Objects.requireNonNull(exchange, "exchange");
    this.maxBodySize = maxBodySize;
  }

  // -- request side --

  @Override
  public String method() {
    if (method == null) {
      method = exchange.getRequestMethod() != null ? exchange.getRequestMethod().toString() : "";
    }
    return method;
  }

  @Override
  public String path() {
    if (path == null) {
      String relative = exchange.getRelativePath();
      path = relative != null ? relative : "/";
    }
    return path;
  }

  @Override
  public Map<String, List<String>> queryParams() {
    if (queryParams == null) {
      queryParams = snapshotQueryParams(exchange);
    }
    return queryParams;
  }

  @Override
  public Map<String, List<String>> headers() {
    if (requestHeaders == null) {
      requestHeaders = snapshotHeaders(exchange);
    }
    return requestHeaders;
  }

  /**
   * The framed request body. Undertow's own {@code MAX_ENTITY_SIZE} (wired from {@code maxBodySize}
   * at server start) can reject the body before the framework's limiter ever reads a byte; that
   * refusal is translated here so the shared 413 mapping answers it instead of an unhandled 500.
   */
  @Override
  public InputStream framedBodyStream() throws IOException {
    if (!exchange.isBlocking()) {
      exchange.startBlocking();
    }
    InputStream in = exchange.getInputStream();
    return new InputStream() {
      @Override
      public int read() throws IOException {
        try {
          return in.read();
        } catch (RequestTooBigException ex) {
          throw new BodyTooLargeException(maxBodySize);
        }
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        try {
          return in.read(b, off, len);
        } catch (RequestTooBigException ex) {
          throw new BodyTooLargeException(maxBodySize);
        }
      }

      @Override
      public void close() throws IOException {
        in.close();
      }
    };
  }

  @Override
  public boolean isSecure() {
    return exchange.isSecure();
  }

  @Override
  public SSLSession sslSession() {
    return exchange.getConnection().getSslSession();
  }

  @Override
  public String remoteAddress() {
    InetSocketAddress source = exchange.getSourceAddress();
    return source != null ? source.getHostString() : "";
  }

  /**
   * The commit fact is Undertow's: {@code isResponseStarted()} turns true when the status line
   * begins going out, which is exactly the moment a prepared head can no longer be replaced.
   */
  @Override
  public boolean isCommitted() {
    return committed || exchange.isResponseStarted();
  }

  // -- response side --

  /**
   * Writes one planned response. Undertow's sender is asynchronous, so this adapter buffers the
   * body it was given — a materialized body is what the minimal adapter can hand over, and the
   * short-circuit in {@link #writeResponseBytes} keeps the {@code Content-Length} the support
   * object declared.
   */
  @Override
  public void writeResponse(ResponsePlan plan) throws IOException {
    ResponseHead head = plan.head();
    applyHead(head);
    if (!head.sendsBodyBytes()) {
      closeBody(plan.body());
      committed = true;
      exchange.endExchange();
      return;
    }
    switch (plan.body()) {
      case ResponseBody.Empty ignored -> {
        committed = true;
        exchange.endExchange();
      }
      case ResponseBody.Bytes bytes -> writeBody(head, bytes.data(), plan.gzip());
      case ResponseBody.Stream stream ->
          writeBody(head, stream.stream().readAllBytes(), plan.gzip());
      case ResponseBody.File file -> writeBody(head, fileBytes(file), plan.gzip());
      case ResponseBody.Channel channel -> {
        try {
          writeBody(head, channelBytes(channel), plan.gzip());
        } finally {
          channel.channel().close();
        }
      }
    }
  }

  private static byte[] fileBytes(ResponseBody.File file) throws IOException {
    try (var channel =
        java.nio.channels.FileChannel.open(
            file.path(),
            java.nio.file.StandardOpenOption.READ,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return channelBytes(file.offset(), file.length(), channel);
    }
  }

  private static byte[] channelBytes(ResponseBody.Channel channel) throws IOException {
    return channelBytes(channel.offset(), channel.length(), channel.channel());
  }

  private static byte[] channelBytes(long offset, long length, java.nio.channels.FileChannel ch)
      throws IOException {
    long available = Math.max(0, ch.size() - offset);
    int count = (int) Math.min(length < 0 ? available : length, available);
    ByteBuffer buffer = ByteBuffer.allocate(count);
    ch.position(offset);
    while (buffer.hasRemaining()) {
      if (ch.read(buffer) < 0) {
        break;
      }
    }
    return buffer.array();
  }

  private void writeBody(ResponseHead head, byte[] body, boolean gzip) throws IOException {
    byte[] data = gzip ? Compression.gzip(body) : body;
    // The head already carries the source length; a gzipped body is the
    // transport's own encoding, so its encoded length is a transport fact.
    if (gzip) {
      applyHeader("Content-Length", Integer.toString(data.length));
    }
    committed = true;
    if (data.length > 0) {
      exchange.getResponseSender().send(ByteBuffer.wrap(data));
    } else {
      exchange.endExchange();
    }
  }

  /**
   * Replays the final head onto Undertow's own header map: validate nothing (the support object
   * already did), clear what a failed attempt left behind, then add every field. {@code
   * Content-Length} is written through Undertow's dedicated setter so its framing state agrees.
   */
  private void applyHead(ResponseHead head) {
    exchange.setStatusCode(head.status());
    var responseHeaders = exchange.getResponseHeaders();
    responseHeaders.clear();
    String contentLength = null;
    for (ResponseHead.Header header : head.headers()) {
      if ("Content-Length".equalsIgnoreCase(header.name())) {
        contentLength = header.value();
        continue;
      }
      responseHeaders.add(HttpString.tryFromString(header.name()), header.value());
    }
    if (contentLength != null) {
      exchange.setResponseContentLength(Long.parseLong(contentLength.trim()));
    } else {
      responseHeaders.remove(Headers.CONTENT_LENGTH);
    }
  }

  private void applyHeader(String name, String value) {
    exchange.getResponseHeaders().put(HttpString.tryFromString(name), value);
  }

  private static void closeBody(ResponseBody body) throws IOException {
    if (body instanceof ResponseBody.Channel channel) {
      channel.channel().close();
    }
  }

  @Override
  public SseEmitter openSse(ResponseHead head) throws IOException {
    applyHead(head);
    committed = true;
    return new SseEmitter(new SseOutputStream(exchange.getResponseSender()));
  }

  /**
   * Serializes non-blocking SSE writes. Undertow's {@link Sender} accepts one in-flight send at a
   * time, so writes are queued and drained from the send callback; a slow client never pins an
   * Undertow worker thread (the blocking-stream path could exhaust the worker pool).
   */
  private static final class SseOutputStream extends OutputStream {
    private static final Logger LOG = LoggerFactory.getLogger(SseOutputStream.class);

    /** High-water mark for queued writes before backpressure is surfaced. */
    private static final int MAX_QUEUED_WRITES = 1024;

    private final Sender sender;
    private final Deque<ByteBuffer> queue = new ArrayDeque<>();
    private boolean sending;
    private boolean closed;

    SseOutputStream(Sender sender) {
      this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public synchronized void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
      if (closed || len == 0) {
        return;
      }
      if (queue.size() >= MAX_QUEUED_WRITES) {
        // The queue only drains via the send callback; a client that stops
        // reading would otherwise buffer without bound. Surface the
        // backpressure to the emitter (the application can close the SSE
        // stream or drop the client).
        throw new IOException(
            "SSE write queue full (" + queue.size() + " queued): client is not keeping up");
      }
      queue.addLast(ByteBuffer.wrap(b, off, len));
      drain();
    }

    @Override
    public synchronized void flush() {
      // Content drains asynchronously via the send callback.
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      drain();
    }

    private void drain() {
      if (sending) {
        return;
      }
      ByteBuffer next = queue.pollFirst();
      if (next == null) {
        if (closed) {
          sender.close();
        }
        return;
      }
      sending = true;
      sender.send(
          next,
          new IoCallback() {
            @Override
            public void onComplete(HttpServerExchange exchange, Sender sender) {
              synchronized (SseOutputStream.this) {
                sending = false;
                drain();
              }
            }

            @Override
            public void onException(HttpServerExchange exchange, Sender sender, IOException ex) {
              LOG.warn("SSE write failed", ex);
              synchronized (SseOutputStream.this) {
                sending = false;
                closed = true;
                queue.clear();
              }
            }
          });
    }
  }

  /**
   * Request-header snapshot keyed by lowercase name. The single conversion from Undertow's native
   * header map: {@link #headers()} and the WebSocket upgrade path both call it, so the HTTP and
   * upgrade views of one request cannot disagree about header casing.
   */
  static Map<String, List<String>> snapshotHeaders(HttpServerExchange exchange) {
    var headerMap = new LinkedHashMap<String, List<String>>();
    for (HttpString name : exchange.getRequestHeaders().getHeaderNames()) {
      Deque<String> values = exchange.getRequestHeaders().get(name);
      headerMap.put(name.toString().toLowerCase(Locale.ROOT), List.copyOf(values));
    }
    return Map.copyOf(headerMap);
  }

  /** Query-parameter snapshot; the same single conversion {@link #queryParams()} uses. */
  static Map<String, List<String>> snapshotQueryParams(HttpServerExchange exchange) {
    return snapshotQuery(exchange.getQueryParameters());
  }

  private static Map<String, List<String>> snapshotQuery(Map<String, Deque<String>> source) {
    if (source.isEmpty()) {
      return Map.of();
    }
    var params = new LinkedHashMap<String, List<String>>(source.size());
    for (var entry : source.entrySet()) {
      params.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(params);
  }
}
