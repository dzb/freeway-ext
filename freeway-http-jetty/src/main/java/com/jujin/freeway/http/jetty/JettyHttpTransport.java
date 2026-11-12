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

import com.jujin.freeway.http.HttpTransport;
import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLSession;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty-backed {@link HttpTransport}: one per request, wrapping the request/response/callback
 * triple it serves. Every response policy decision (status, headers, Content-Length, gzip, Date)
 * lives in the composed {@code HttpContextSupport}; this class only moves the bytes onto Jetty's
 * response.
 *
 * <p>Streaming and file responses inherit the SPI's buffering defaults — the pre-refactor adapter
 * buffered them too.
 */
final class JettyHttpTransport implements HttpTransport {
  private static final Logger LOG = LoggerFactory.getLogger(JettyHttpTransport.class);

  private final Request request;
  private final Response response;
  private final Callback callback;

  private Map<String, List<String>> queryParams;
  private Map<String, List<String>> requestHeaders;

  JettyHttpTransport(Request request, Response response, Callback callback) {
    this.request = Objects.requireNonNull(request, "request");
    this.response = Objects.requireNonNull(response, "response");
    this.callback = Objects.requireNonNull(callback, "callback");
  }

  // -- request side --

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
  public Map<String, List<String>> queryParams() {
    if (queryParams == null) {
      queryParams = parseQueryParams(request);
    }
    return queryParams;
  }

  @Override
  public Map<String, List<String>> headers() {
    if (requestHeaders == null) {
      LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();
      for (String name : request.getHeaders().getFieldNamesCollection()) {
        List<String> values = request.getHeaders().getValuesList(name);
        map.put(name.toLowerCase(Locale.ROOT), values != null ? List.copyOf(values) : List.of());
      }
      requestHeaders = Map.copyOf(map);
    }
    return requestHeaders;
  }

  @Override
  public InputStream bodyStream() {
    return Request.asInputStream(request);
  }

  @Override
  public boolean isSecure() {
    return request.isSecure();
  }

  @Override
  public SSLSession sslSession() {
    // Jetty's TLS customizer attaches the negotiated session to the request;
    // without it (plaintext, or a customizer that did not run) there is none.
    Object data = request.getAttribute(EndPoint.SslSessionData.ATTRIBUTE);
    return data instanceof EndPoint.SslSessionData ssl ? ssl.sslSession() : null;
  }

  @Override
  public String remoteAddress() {
    String remote = Request.getRemoteAddr(request);
    return remote != null ? remote : "";
  }

  // -- response side --

  @Override
  public void writeResponse(ResponseHead head, byte[] body) throws IOException {
    applyHead(head);
    if (head.sendsBodyBytes() && body.length > 0) {
      response.write(true, ByteBuffer.wrap(body), callback);
    } else {
      callback.succeeded();
    }
  }

  @Override
  public SseEmitter openSse(ResponseHead head) throws IOException {
    applyHead(head);
    return new SseEmitter(new SseOutputStream(response, callback));
  }

  /**
   * Writes the committed head onto the response: status, every header in wire order (the head is
   * already the final state — the support object applied set/replace/add semantics), and {@code
   * Content-Length} separately so the container never sees it twice.
   */
  private void applyHead(ResponseHead head) {
    response.setStatus(head.status());
    var headers = response.getHeaders();
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
      headers.remove(entry.getKey());
      for (String value : entry.getValue()) {
        headers.add(entry.getKey(), value);
      }
    }
    if (head.hasHeader("Content-Length")) {
      headers.put(HttpHeader.CONTENT_LENGTH, head.header("Content-Length").trim());
    } else {
      headers.remove(HttpHeader.CONTENT_LENGTH);
    }
  }

  private static Map<String, List<String>> parseQueryParams(Request request) {
    Fields fields = Request.extractQueryParameters(request);
    LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
    for (Fields.Field field : fields) {
      params.put(field.getName(), List.copyOf(field.getValues()));
    }
    return Map.copyOf(params);
  }

  /** Non-blocking SSE writes: {@code last=false} keeps the content stream open until close(). */
  private static final class SseOutputStream extends OutputStream {
    private static final Logger LOG = LoggerFactory.getLogger(SseOutputStream.class);

    private final Response response;
    private final Callback callback;
    private final Callback writeCallback =
        Callback.from(
            () -> {
              // Write handed to the Jetty channel; nothing to do.
            },
            ex -> LOG.warn("SSE write failed", ex));

    SseOutputStream(Response response, Callback callback) {
      this.response = Objects.requireNonNull(response, "response");
      this.callback = Objects.requireNonNull(callback, "callback");
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (len == 0) return;
      response.write(false, ByteBuffer.wrap(b, off, len), writeCallback);
    }

    @Override
    public void flush() {
      // Jetty hands each write to the channel immediately.
    }

    @Override
    public void close() {
      // last=true ends the content stream; completing the request callback
      // releases the request so graceful shutdown can proceed.
      response.write(true, ByteBuffer.allocate(0), callback);
    }
  }
}
