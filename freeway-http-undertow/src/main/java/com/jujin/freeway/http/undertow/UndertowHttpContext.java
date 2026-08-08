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
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
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
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UndertowHttpContext extends HttpContext {

  private HttpServerExchange exchange;
  private RequestContext requestContext;
  private Map<String, List<String>> queryParams;
  private String method;
  private String path;
  private Map<String, List<String>> requestHeaders;
  private byte[] cachedBody;
  private int responseStatus = 200;
  private boolean responded;

  /** Pooled constructor — call {@link #reset} before use. */
  UndertowHttpContext(JsonCodec jsonCodec, Coercer coercer) {
    super(jsonCodec, coercer);
  }

  /** Reinitializes all per-request state for object reuse. */
  void reset(HttpServerExchange exchange, RequestContext requestContext) {
    this.exchange = Objects.requireNonNull(exchange, "exchange");
    this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    this.queryParams = null; // lazy — PING never accesses
    this.method = exchange.getRequestMethod() != null ? exchange.getRequestMethod().toString() : "";
    String rel = exchange.getRelativePath();
    this.path = rel != null ? rel : "/";
    this.requestHeaders = null; // lazy — PING never accesses
    this.cachedBody = null;
    this.responseStatus = 200;
    this.responded = false;
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
  public Optional<String> queryParam(String name) {
    if (queryParams == null) {
      queryParams = snapshotQuery(exchange.getQueryParameters());
    }
    List<String> values = queryParams.get(name);
    return Optional.ofNullable(values != null && !values.isEmpty() ? values.getFirst() : null);
  }

  @Override
  public List<String> queryParams(String name) {
    if (queryParams == null) {
      queryParams = snapshotQuery(exchange.getQueryParameters());
    }
    return queryParams.getOrDefault(name, List.of());
  }

  @Override
  public Map<String, List<String>> queryParams() {
    if (queryParams == null) {
      queryParams = snapshotQuery(exchange.getQueryParameters());
    }
    return queryParams;
  }

  @Override
  public Optional<String> header(String name) {
    return Optional.ofNullable(exchange.getRequestHeaders().getFirst(name));
  }

  @Override
  public List<String> headers(String name) {
    Deque<String> values = exchange.getRequestHeaders().get(name);
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    if (values.size() == 1) {
      return List.of(values.getFirst());
    }
    return new ArrayList<>(values);
  }

  @Override
  public Map<String, List<String>> headers() {
    if (requestHeaders == null) {
      var headerMap = new LinkedHashMap<String, List<String>>();
      for (HttpString name : exchange.getRequestHeaders().getHeaderNames()) {
        Deque<String> values = exchange.getRequestHeaders().get(name);
        headerMap.put(name.toString().toLowerCase(Locale.ROOT), List.copyOf(values));
      }
      requestHeaders = Map.copyOf(headerMap);
    }
    return requestHeaders;
  }

  @Override
  public byte[] body() throws IOException {
    if (cachedBody == null) {
      if (!exchange.isBlocking()) {
        exchange.startBlocking();
      }
      try (InputStream in = exchange.getInputStream()) {
        cachedBody = readBodyLimited(in);
      } catch (RequestTooBigException ex) {
        // Undertow's parser-level MAX_ENTITY_SIZE (propagated from
        // maxBodySize) rejects the body before readBodyLimited's own check
        // fires. Normalize to the Freeway contract so the core ExceptionMapper
        // answers 413 instead of an unhandled 500.
        throw new BodyTooLargeException(maxBodySize);
      }
    }
    return cachedBody;
  }

  @Override
  public SseEmitter sse() throws IOException {
    exchange.setStatusCode(200);
    setupSseHeaders();
    responded = true;
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

  @Override
  public RequestContext requestContext() {
    return requestContext;
  }

  @Override
  public HttpContext status(int status) {
    this.responseStatus = status;
    exchange.setStatusCode(status);
    return this;
  }

  @Override
  public int status() {
    return responseStatus;
  }

  @Override
  protected String responseHeader(String name) {
    return exchange.getResponseHeaders().getFirst(name);
  }

  @Override
  public HttpContext setHeader(String name, String value) {
    validateHeaderName(name);
    validateHeaderValue(value);
    HttpString headerName = HttpString.tryFromString(name.toLowerCase(Locale.ROOT));
    if (headerName == null) {
      // tryFromString rejects characters above 255; fail fast with a clear
      // message instead of letting HeaderMap.put NPE.
      throw new IllegalArgumentException("Invalid header name: " + name);
    }
    exchange.getResponseHeaders().put(headerName, value);
    return this;
  }

  @Override
  public HttpContext output(byte[] data) throws IOException {
    if (responded) {
      return this;
    }
    boolean head = "HEAD".equalsIgnoreCase(method);
    // HEAD must report the same Content-Length as GET (RFC 7231 §4.3.2);
    // 204/205/304 have no body and no Content-Length.
    boolean bodyAllowed = responseStatus != 204 && responseStatus != 205 && responseStatus != 304;
    if (bodyAllowed) {
      exchange.setResponseContentLength(data.length);
    } else {
      // 204/205/304 must not carry Content-Length even if the handler set it.
      exchange.getResponseHeaders().remove(Headers.CONTENT_LENGTH);
    }
    responded = true;
    if (bodyAllowed && !head && data.length > 0) {
      exchange.getResponseSender().send(ByteBuffer.wrap(data));
    } else {
      exchange.endExchange();
    }
    return this;
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
