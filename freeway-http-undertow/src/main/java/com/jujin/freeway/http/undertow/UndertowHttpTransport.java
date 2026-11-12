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

import com.jujin.freeway.http.HttpTransport;
import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.sse.SseEmitter;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RequestTooBigException;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * Undertow-backed {@link HttpTransport}: one per request, wrapping the exchange it serves. Every
 * response policy decision (status, headers, Content-Length, gzip, Date) lives in the composed
 * {@code HttpContextSupport}; this class only moves the bytes onto Undertow's exchange.
 *
 * <p>Streaming and file responses inherit the SPI's buffering defaults — Undertow has no sendfile
 * fast path through this adapter, which matches the pre-refactor behaviour.
 */
final class UndertowHttpTransport implements HttpTransport {
  private static final Logger LOG = LoggerFactory.getLogger(UndertowHttpTransport.class);

  private final HttpServerExchange exchange;

  /** Undertow's own ceiling (MAX_ENTITY_SIZE, fixed at start); translated to the Freeway 413. */
  private final long nativeBodyLimit;

  private Map<String, List<String>> queryParams;
  private Map<String, List<String>> requestHeaders;

  UndertowHttpTransport(HttpServerExchange exchange, long nativeBodyLimit) {
    this.exchange = Objects.requireNonNull(exchange, "exchange");
    this.nativeBodyLimit = nativeBodyLimit;
  }

  // -- request side --

  @Override
  public String method() {
    return exchange.getRequestMethod() != null ? exchange.getRequestMethod().toString() : "";
  }

  @Override
  public String path() {
    String relative = exchange.getRelativePath();
    return relative != null ? relative : "/";
  }

  @Override
  public Map<String, List<String>> queryParams() {
    if (queryParams == null) {
      queryParams = snapshotQuery(exchange.getQueryParameters());
    }
    return queryParams;
  }

  @Override
  public Map<String, List<String>> headers() {
    if (requestHeaders == null) {
      var headerMap = new LinkedHashMap<String, List<String>>();
      for (HttpString name : exchange.getRequestHeaders().getHeaderNames()) {
        headerMap.put(
            name.toString().toLowerCase(Locale.ROOT),
            List.copyOf(exchange.getRequestHeaders().get(name)));
      }
      requestHeaders = Map.copyOf(headerMap);
    }
    return requestHeaders;
  }

  @Override
  public InputStream bodyStream() throws IOException {
    if (!exchange.isBlocking()) {
      exchange.startBlocking();
    }
    return new BodyStream(exchange.getInputStream());
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

  // -- response side --

  @Override
  public void writeResponse(ResponseHead head, byte[] body) throws IOException {
    applyHead(head);
    if (head.sendsBodyBytes() && body.length > 0) {
      exchange.getResponseSender().send(ByteBuffer.wrap(body));
    } else {
      exchange.endExchange();
    }
  }

  @Override
  public SseEmitter openSse(ResponseHead head) throws IOException {
    applyHead(head);
    return new SseEmitter(new SseOutputStream(exchange.getResponseSender()));
  }

  /**
   * Writes the committed head onto the exchange: status, every header in wire order (the head is
   * already the final state — the support object applied set/replace/add semantics), and {@code
   * Content-Length} separately so the container never sees it twice.
   */
  private void applyHead(ResponseHead head) {
    exchange.setStatusCode(head.status());
    HeaderMap responseHeaders = exchange.getResponseHeaders();
    Map<String, List<String>> byName = new LinkedHashMap<>();
    for (ResponseHead.Header header : head.headers()) {
      if ("Content-Length".equalsIgnoreCase(header.name())) {
        continue;
      }
      byName
          .computeIfAbsent(header.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>(2))
          .add(header.value());
    }
    for (Map.Entry<String, List<String>> entry : byName.entrySet()) {
      HttpString name = HttpString.tryFromString(entry.getKey());
      if (name == null) {
        // tryFromString rejects characters above 255; the support object
        // already validated the name as an RFC 7230 token, so this is
        // unreachable — fail loud rather than silently dropping a header.
        throw new IllegalArgumentException("Invalid header name: " + entry.getKey());
      }
      responseHeaders.remove(name);
      for (String value : entry.getValue()) {
        responseHeaders.add(name, value);
      }
    }
    if (head.hasHeader("Content-Length")) {
      exchange.setResponseContentLength(Long.parseLong(head.header("Content-Length").trim()));
    } else {
      responseHeaders.remove(Headers.CONTENT_LENGTH);
    }
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

  /**
   * Translates Undertow's parser-level {@link RequestTooBigException} (propagated from {@code
   * MAX_ENTITY_SIZE}, fixed at server start) into the Freeway {@link BodyTooLargeException}, so the
   * core {@code ErrorHandler} answers 413 instead of an unhandled 500. The support object's own
   * limiter runs on top of this stream; whichever ceiling the body crosses first wins, and both
   * report the same contract type.
   */
  private final class BodyStream extends FilterInputStream {
    BodyStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      try {
        return in.read();
      } catch (RequestTooBigException ex) {
        throw tooLarge();
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      try {
        return in.read(b, off, len);
      } catch (RequestTooBigException ex) {
        throw tooLarge();
      }
    }

    @Override
    public long skip(long n) throws IOException {
      try {
        return in.skip(n);
      } catch (RequestTooBigException ex) {
        throw tooLarge();
      }
    }

    private BodyTooLargeException tooLarge() {
      LOG.debug("Undertow rejected the request body at {} bytes", nativeBodyLimit);
      return new BodyTooLargeException(nativeBodyLimit);
    }
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
}
