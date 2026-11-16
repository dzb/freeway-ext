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

package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.HttpTransport;
import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.ResponsePlan;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSession;

/**
 * A minimal {@link HttpTransport} for the JMH benchmarks: it holds a request snapshot and discards
 * the response, so a benchmark measures the shared policy object rather than a socket.
 *
 * <p>The engine composes this the same way a real adapter does — one transport plus one {@code
 * HttpExchangeSupport} per exchange — so the measured path is the production one (no pooled
 * context, no reset).
 */
final class BenchTransport implements HttpTransport {

  private final String method;
  private final String path;
  private final Map<String, List<String>> queryParams;
  private final Map<String, List<String>> headers;
  private final InputStream body;
  private volatile boolean committed;

  BenchTransport(
      String method,
      String path,
      Map<String, List<String>> queryParams,
      Map<String, List<String>> headers,
      InputStream body) {
    this.method = method;
    this.path = path;
    this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
    this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    this.body = body != null ? body : InputStream.nullInputStream();
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
  public Map<String, List<String>> queryParams() {
    return queryParams;
  }

  @Override
  public Map<String, List<String>> headers() {
    return headers;
  }

  @Override
  public InputStream framedBodyStream() {
    return body;
  }

  @Override
  public boolean isSecure() {
    return false;
  }

  @Override
  public SSLSession sslSession() {
    return null;
  }

  @Override
  public String remoteAddress() {
    return "127.0.0.1";
  }

  @Override
  public boolean isCommitted() {
    return committed;
  }

  @Override
  public void writeResponse(ResponsePlan plan) {
    committed = true;
  }

  @Override
  public SseEmitter openSse(ResponseHead head) {
    committed = true;
    return new SseEmitter(new java.io.ByteArrayOutputStream());
  }
}
