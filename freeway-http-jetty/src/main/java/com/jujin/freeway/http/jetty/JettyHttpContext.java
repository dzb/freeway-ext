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
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Jetty-backed {@link HttpContext} implementation, pooled per thread. */
final class JettyHttpContext extends HttpContext {
  private static final Logger LOG = LoggerFactory.getLogger(JettyHttpContext.class);

  private Request request;
  private Response response;
  private Callback callback;
  private RequestContext requestContext;
  private Map<String, List<String>> queryParams;
  private volatile byte[] cachedBody;
  private int responseStatus = 200;
  private volatile boolean responded;

  /** Pooled constructor — call {@link #reset} before use. */
  JettyHttpContext(JsonCodec jsonCodec, Coercer coercer) {
    super(jsonCodec, coercer);
  }

  /** Reinitializes all per-request state for object reuse. */
  void reset(Request request, Response response, RequestContext requestContext, Callback callback) {
    this.request = Objects.requireNonNull(request, "request");
    this.response = Objects.requireNonNull(response, "response");
    this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    this.callback = Objects.requireNonNull(callback, "callback");
    this.queryParams = parseQueryParams(request);
    this.cachedBody = null;
    this.responseStatus = 200;
    this.responded = false;
  }

  @Override
  public String method() {
    return request.getMethod() != null ? request.getMethod() : "";
  }

  @Override
  public String path() {
    String path = request.getHttpURI() != null ? request.getHttpURI().getPath() : null;
    return path != null ? path : "/";
  }

  @Override
  public Optional<String> queryParam(String name) {
    List<String> values = queryParams.get(name);
    return Optional.ofNullable(values != null && !values.isEmpty() ? values.get(0) : null);
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
  public Optional<String> header(String name) {
    return Optional.ofNullable(request.getHeaders().get(name));
  }

  @Override
  public List<String> headers(String name) {
    List<String> values = request.getHeaders().getValuesList(name);
    return values != null ? List.copyOf(values) : List.of();
  }

  @Override
  public byte[] body() throws IOException {
    if (cachedBody == null) {
      try (InputStream input = Request.asInputStream(request)) {
        cachedBody = readBodyLimited(input);
      }
    }
    return cachedBody;
  }

  @Override
  public SseEmitter sse() throws IOException {
    response.setStatus(200);
    setupSseHeaders();
    responded = true;
    return new SseEmitter(
        new OutputStream() {
          private final Callback writeCallback =
              Callback.from(
                  () -> {
                    // Write handed to the Jetty channel; nothing to do.
                  },
                  ex -> LOG.warn("SSE write failed", ex));

          @Override
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            // last=false keeps the response stream open so further events can
            // be written; the response completes on close().
            response.write(false, ByteBuffer.wrap(b, off, len), writeCallback);
          }

          @Override
          public void flush() {
            // Jetty hands each write to the channel immediately.
          }

          @Override
          public void close() {
            // last=true ends the content stream; completing the request
            // callback releases the request so graceful shutdown can proceed
            // (same pattern as the one-shot output() path).
            response.write(true, ByteBuffer.allocate(0), callback);
          }
        });
  }

  @Override
  public Map<String, List<String>> headers() {
    LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
    for (String name : request.getHeaders().getFieldNamesCollection()) {
      List<String> values = request.getHeaders().getValuesList(name);
      map.put(
          name.toLowerCase(java.util.Locale.ROOT),
          values != null ? List.copyOf(values) : List.of());
    }
    return Map.copyOf(map);
  }

  @Override
  protected String responseHeader(String name) {
    return response.getHeaders().get(name);
  }

  @Override
  public RequestContext requestContext() {
    return requestContext;
  }

  @Override
  public HttpContext status(int status) {
    this.responseStatus = status;
    response.setStatus(status);
    return this;
  }

  @Override
  public int status() {
    return responseStatus;
  }

  boolean responded() {
    return responded;
  }

  @Override
  public HttpContext headerSet(String name, String value) {
    validateHeaderName(name);
    validateHeaderValue(value);
    response.getHeaders().put(name, value);
    return this;
  }

  @Override
  public HttpContext output(byte[] data) throws IOException {
    if (responded) {
      return this;
    }
    boolean headRequest = "HEAD".equalsIgnoreCase(method());
    // HEAD must report the same Content-Length as GET (RFC 7231 §4.3.2);
    // 204/205/304 have no body and no Content-Length.
    boolean bodyAllowed = responseStatus != 204 && responseStatus != 205 && responseStatus != 304;
    if (bodyAllowed) {
      response.getHeaders().put(HttpHeader.CONTENT_LENGTH, String.valueOf(data.length));
    } else {
      // 204/205/304 must not carry Content-Length even if the handler set it.
      response.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
    }
    responded = true;
    if (headRequest
        || responseStatus == 204
        || responseStatus == 205
        || responseStatus == 304
        || data.length == 0) {
      callback.succeeded();
      return this;
    }
    response.write(true, ByteBuffer.wrap(data), callback);
    return this;
  }

  private static Map<String, List<String>> parseQueryParams(Request request) {
    Fields fields = Request.extractQueryParameters(request);
    LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
    for (Fields.Field field : fields) {
      params.put(field.getName(), List.copyOf(field.getValues()));
    }
    return Map.copyOf(params);
  }
}
