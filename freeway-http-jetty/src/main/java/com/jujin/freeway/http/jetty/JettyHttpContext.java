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
import com.jujin.freeway.http.AbstractHttpContext;
import com.jujin.freeway.http.HttpResponse;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.MediaTypes;
import com.jujin.freeway.http.engine.ResponseFraming;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Jetty-backed {@link com.jujin.freeway.http.HttpContext} implementation, pooled per thread. */
final class JettyHttpContext extends AbstractHttpContext {
  private static final Logger LOG = LoggerFactory.getLogger(JettyHttpContext.class);

  private Request request;
  private Response response;
  private Callback callback;
  private Map<String, List<String>> queryParams;
  private volatile byte[] cachedBody;
  private int responseStatus = 200;
  private volatile boolean responded;

  /** gzip policy from the server config; set once per dispatch. */
  private HttpServerConfig.CompressionConfig compression =
      HttpServerConfig.CompressionConfig.DEFAULT;

  /** Pooled constructor — call {@link #reset} before use. */
  JettyHttpContext(JsonCodec jsonCodec, Coercer coercer) {
    super(jsonCodec, coercer);
  }

  /** Reinitializes all per-request state for object reuse. */
  void reset(Request request, Response response, String correlationId, Callback callback) {
    this.request = Objects.requireNonNull(request, "request");
    this.response = Objects.requireNonNull(response, "response");
    this.callback = Objects.requireNonNull(callback, "callback");
    setCorrelationId(correlationId);
    this.queryParams = parseQueryParams(request);
    this.cachedBody = null;
    this.responseStatus = 200;
    this.responded = false;
    this.pathVariables.clear();
  }

  /** Sets the gzip compression policy for this exchange (from the server config). */
  void setCompression(HttpServerConfig.CompressionConfig compression) {
    if (compression != null) {
      this.compression = compression;
    }
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
        cachedBody = readBody(input);
      }
    }
    return cachedBody;
  }

  @Override
  public SseEmitter sse() throws IOException {
    response.setStatus(responseStatus);
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
      map.put(name.toLowerCase(Locale.ROOT), values != null ? List.copyOf(values) : List.of());
    }
    return Map.copyOf(map);
  }

  @Override
  protected String responseHeader(String name) {
    return response.getHeaders().get(name);
  }

  @Override
  public HttpResponse setStatus(int status) {
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
  public HttpResponse setHeader(String name, String value) {
    if (responded) return this;
    validateHeaderName(name);
    validateHeaderValue(value);
    response.getHeaders().put(name, value);
    return this;
  }

  @Override
  public HttpResponse addHeader(String name, String value) {
    if (responded) return this;
    validateHeaderName(name);
    validateHeaderValue(value);
    response.getHeaders().add(name, value);
    return this;
  }

  @Override
  public boolean isResponded() {
    return responded;
  }

  @Override
  public HttpResponse output(byte[] data) throws IOException {
    if (responded) {
      return this;
    }
    boolean bodyAllowed = allowsResponseBody();
    // Mirror the built-in engine's buffered gzip path (ResponseFraming):
    // same gates (status, min-size, Accept-Encoding, compressible type),
    // same headers (Content-Encoding + merged Vary), Content-Length set to
    // the compressed length.
    byte[] body = data;
    if (ResponseFraming.shouldGzip(
        compression,
        responseStatus,
        bodyAllowed,
        data.length,
        acceptsGzip(),
        compressibleContentType())) {
      body = gzip(data);
      setHeader("Content-Encoding", "gzip");
      addVary("Accept-Encoding");
    }
    if (bodyAllowed) {
      response.getHeaders().put(HttpHeader.CONTENT_LENGTH, String.valueOf(body.length));
    } else {
      // 204/205/304 must not carry Content-Length even if the handler set it.
      response.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
    }
    responded = true;
    if (suppressBodyBytes(method()) || body.length == 0) {
      callback.succeeded();
      return this;
    }
    response.write(true, ByteBuffer.wrap(body), callback);
    return this;
  }

  /**
   * True when the client explicitly accepts gzip (same semantics as the built-in engine: an absent
   * Accept-Encoding header is treated as "no preference" and does not compress).
   */
  private boolean acceptsGzip() {
    String acceptEncoding = request.getHeaders().get(HttpHeader.ACCEPT_ENCODING);
    if (acceptEncoding == null) {
      return false;
    }
    for (String part : acceptEncoding.split(",")) {
      String token = part.trim();
      int q = token.indexOf(';');
      String name = q < 0 ? token : token.substring(0, q).trim();
      if ("gzip".equalsIgnoreCase(name)) {
        if (q < 0) {
          return true;
        }
        return !qValueIsZero(token.substring(q + 1));
      }
    }
    return false;
  }

  private static boolean qValueIsZero(String params) {
    for (String part : params.split(";")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length == 2 && "q".equals(kv[0].trim())) {
        try {
          return Double.parseDouble(kv[1].trim()) == 0.0;
        } catch (NumberFormatException e) {
          return false;
        }
      }
    }
    return false;
  }

  private boolean compressibleContentType() {
    return MediaTypes.isCompressibleContentType(response.getHeaders().get(HttpHeader.CONTENT_TYPE));
  }

  private static byte[] gzip(byte[] data) throws IOException {
    var out = new ByteArrayOutputStream(Math.max(32, data.length / 2));
    try (var gzip = new GZIPOutputStream(out)) {
      gzip.write(data);
    }
    return out.toByteArray();
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
