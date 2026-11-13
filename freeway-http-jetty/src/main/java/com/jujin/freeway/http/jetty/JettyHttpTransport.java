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

import com.jujin.freeway.http.Compression;
import com.jujin.freeway.http.HttpTransport;
import com.jujin.freeway.http.ResponseBody;
import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.ResponsePlan;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
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
 * Jetty's half of one exchange: it reads the native request facts and writes the planned response.
 * Everything shared — status and header validation, {@code Date}, {@code Content-Length}, the gzip
 * decision, {@code Vary}, the body limit — lives in the {@code HttpExchangeSupport} composed over
 * this object. One transport per exchange; nothing is pooled and there is no reset.
 */
final class JettyHttpTransport implements HttpTransport {
  private static final Logger LOG = LoggerFactory.getLogger(JettyHttpTransport.class);

  private final Request request;
  private final Response response;
  private final Callback callback;

  private Map<String, List<String>> queryParams;
  private Map<String, List<String>> requestHeaders;
  private volatile boolean committed;

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
      Fields fields = Request.extractQueryParameters(request);
      LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
      for (Fields.Field field : fields) {
        params.put(field.getName(), List.copyOf(field.getValues()));
      }
      queryParams = Map.copyOf(params);
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
  public InputStream framedBodyStream() throws IOException {
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

  /** Jetty's own commit fact: true once the response head has begun to go out. */
  @Override
  public boolean isCommitted() {
    return committed || response.isCommitted();
  }

  // -- response side --

  @Override
  public void writeResponse(ResponsePlan plan) throws IOException {
    ResponseHead head = plan.head();
    applyHead(head);
    if (!head.sendsBodyBytes()) {
      closeBody(plan.body());
      committed = true;
      callback.succeeded();
      return;
    }
    switch (plan.body()) {
      case ResponseBody.Empty ignored -> {
        committed = true;
        callback.succeeded();
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
    try (FileChannel channel =
        FileChannel.open(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      return channelBytes(file.offset(), file.length(), channel);
    }
  }

  private static byte[] channelBytes(ResponseBody.Channel channel) throws IOException {
    return channelBytes(channel.offset(), channel.length(), channel.channel());
  }

  private static byte[] channelBytes(long offset, long length, FileChannel channel)
      throws IOException {
    long available = Math.max(0, channel.size() - offset);
    int count = (int) Math.min(length < 0 ? available : length, available);
    ByteBuffer buffer = ByteBuffer.allocate(count);
    channel.position(offset);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        break;
      }
    }
    return buffer.array();
  }

  private void writeBody(ResponseHead head, byte[] body, boolean gzip) throws IOException {
    byte[] data = gzip ? Compression.gzip(body) : body;
    if (gzip) {
      // The encoded length is the transport's own fact; the support object
      // dropped Content-Length when it decided to compress.
      response.getHeaders().put(HttpHeader.CONTENT_LENGTH, String.valueOf(data.length));
    }
    committed = true;
    if (data.length == 0) {
      callback.succeeded();
      return;
    }
    response.write(true, ByteBuffer.wrap(data), callback);
  }

  /**
   * Replays the final head onto Jetty's own header map: clear what a failed attempt left behind,
   * then add every field. {@code Content-Length} is written through the dedicated header so Jetty's
   * framing state agrees.
   */
  private void applyHead(ResponseHead head) {
    response.setStatus(head.status());
    var responseHeaders = response.getHeaders();
    responseHeaders.clear();
    for (ResponseHead.Header header : head.headers()) {
      responseHeaders.add(header.name(), header.value());
    }
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
            // (same pattern as the one-shot writeResponse path).
            response.write(true, ByteBuffer.allocate(0), callback);
          }
        });
  }

  /** Every header value for the name, in wire order. */
  static List<String> headerValues(Request request, String name) {
    List<String> values = request.getHeaders().getValuesList(name);
    return values != null ? new ArrayList<>(values) : List.of();
  }
}
