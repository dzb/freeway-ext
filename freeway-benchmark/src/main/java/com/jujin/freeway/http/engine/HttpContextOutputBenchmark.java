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

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpContextSupport;
import com.jujin.freeway.http.HttpServerConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH benchmark for {@link HttpContextSupport} response output paths.
 *
 * <p>Covers plain-text, JSON, and not-found responses as well as request-body reading combined with
 * output and the {@code sendJson} convenience shortcut. Each operation builds a fresh exchange —
 * production does the same since the composition refactor, and a committed response cannot be
 * reused anyway ({@code responded} would swallow every later output).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class HttpContextOutputBenchmark {

  private static final JsonCodec JSON = new JsonCodecDefault();
  private static final Coercer COERCER = new CoercerDefault();
  private static final byte[] PONG = "pong".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LARGE_BODY =
      "large-body-".repeat(2000).getBytes(StandardCharsets.UTF_8);

  private static final Map<String, List<String>> REQUEST_HEADERS;

  static {
    REQUEST_HEADERS = new LinkedHashMap<>();
    REQUEST_HEADERS.put("Host", List.of("127.0.0.1"));
    REQUEST_HEADERS.put("Connection", List.of("keep-alive"));
    REQUEST_HEADERS.put("User-Agent", List.of("freeway-bench"));
    REQUEST_HEADERS.put("Accept", List.of("*/*"));
  }

  private OutputStream sink;

  @Setup
  public void setup() {
    sink = OutputStream.nullOutputStream();
  }

  /** A fresh exchange writing onto the shared null sink. */
  private HttpContextSupport exchange(
      String method, String path, InputStream body, long contentLength) {
    var transport =
        new HttpTransportImpl(
            method,
            path,
            null,
            REQUEST_HEADERS,
            body,
            contentLength,
            false,
            sink,
            true,
            false,
            null,
            "");
    return new HttpContextSupport(
        transport,
        JSON,
        COERCER,
        HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
        HttpServerConfig.CompressionConfig.DEFAULT,
        null);
  }

  // --- Existing scenarios (preserved) ---

  @Benchmark
  public HttpContext sendPongText() throws IOException {
    HttpContextSupport ctx = exchange("GET", "/ping", InputStream.nullInputStream(), -1);
    ctx.setStatus(200);
    ctx.setHeader("Content-Type", "text/plain; charset=utf-8");
    ctx.output(PONG);
    return ctx;
  }

  @Benchmark
  public HttpContext sendPongJson() throws IOException {
    HttpContextSupport ctx = exchange("GET", "/ping", InputStream.nullInputStream(), -1);
    ctx.setStatus(200);
    ctx.outputJson(Map.of("status", "ok"));
    return ctx;
  }

  @Benchmark
  public HttpContext sendNotFound() throws IOException {
    HttpContextSupport ctx = exchange("GET", "/missing", InputStream.nullInputStream(), -1);
    ctx.setStatus(404);
    ctx.output("Not Found".getBytes(StandardCharsets.UTF_8));
    return ctx;
  }

  // --- New scenarios ---

  /** Read request body then output response — simulates POST handler. */
  @Benchmark
  public HttpContext readBodyThenOutput() throws IOException {
    HttpContextSupport ctx =
        exchange("POST", "/api/data", new ByteArrayInputStream(LARGE_BODY), LARGE_BODY.length);
    ctx.body(); // reads + caches body
    ctx.setStatus(201);
    ctx.setHeader("Content-Type", "application/json");
    ctx.output(PONG);
    return ctx;
  }

  /** Convenience shortcut: sendJson(status, value). */
  @Benchmark
  public HttpContext sendJsonShortcut() throws IOException {
    HttpContextSupport ctx = exchange("GET", "/api/resource", InputStream.nullInputStream(), -1);
    ctx.sendJson(200, Map.of("id", 42, "name", "Alice"));
    return ctx;
  }
}
