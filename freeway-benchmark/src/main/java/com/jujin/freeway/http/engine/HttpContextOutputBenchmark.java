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
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.HttpExchangeSupport;
import com.jujin.freeway.http.HttpServerConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * JMH benchmark for {@link HttpExchangeSupport} response output paths.
 *
 * <p>Covers plain-text, JSON, and not-found responses as well as request-body reading combined with
 * output and the {@code sendJson} convenience shortcut.
 *
 * <p>Each operation builds a fresh exchange over its own {@link BenchTransport}, because production
 * builds one per request: the commit guard, the header store and the body cache are per-exchange
 * state, so reusing one object would measure a state machine no engine has (and the second
 * operation would be an ignored duplicate output).
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
    REQUEST_HEADERS.put("host", List.of("127.0.0.1"));
    REQUEST_HEADERS.put("connection", List.of("keep-alive"));
    REQUEST_HEADERS.put("user-agent", List.of("freeway-bench"));
    REQUEST_HEADERS.put("accept", List.of("*/*"));
  }

  @Setup
  public void setup() {
    // Warm the shared codec/coercer so the first measured op does not pay
    // their class initialization.
    JSON.toJson(Map.of("status", "ok"));
    COERCER.coerce("1", Integer.class);
  }

  private static HttpExchangeSupport exchange(String method, String path, InputStream body) {
    return new HttpExchangeSupport(
        new BenchTransport(method, path, null, REQUEST_HEADERS, body),
        JSON,
        COERCER,
        HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
        HttpServerConfig.CompressionConfig.DEFAULT,
        null);
  }

  // --- Buffered body shapes ---

  @Benchmark
  public HttpExchange sendPongText() throws IOException {
    HttpExchangeSupport ctx = exchange("GET", "/ping", InputStream.nullInputStream());
    ctx.setStatus(200);
    ctx.setHeader("Content-Type", "text/plain; charset=utf-8");
    ctx.body(PONG);
    return ctx;
  }

  @Benchmark
  public HttpExchange sendPongJson() throws IOException {
    HttpExchangeSupport ctx = exchange("GET", "/ping", InputStream.nullInputStream());
    ctx.setStatus(200);
    ctx.json(Map.of("status", "ok"));
    return ctx;
  }

  @Benchmark
  public HttpExchange sendNotFound() throws IOException {
    HttpExchangeSupport ctx = exchange("GET", "/missing", InputStream.nullInputStream());
    ctx.setStatus(404);
    ctx.body("Not Found".getBytes(StandardCharsets.UTF_8));
    return ctx;
  }

  // --- Request body + output ---

  /** Read request body then output response — simulates POST handler. */
  @Benchmark
  public HttpExchange readBodyThenOutput() throws IOException {
    HttpExchangeSupport ctx = exchange("POST", "/api/data", new ByteArrayInputStream(LARGE_BODY));
    ctx.body(); // reads + caches body
    ctx.setStatus(201);
    ctx.setHeader("Content-Type", "application/json");
    ctx.body(PONG);
    return ctx;
  }

  /** Convenience shortcut: sendJson(status, value). */
  @Benchmark
  public HttpExchange sendJsonShortcut() throws IOException {
    HttpExchangeSupport ctx = exchange("GET", "/api/resource", InputStream.nullInputStream());
    ctx.sendJson(200, Map.of("id", 42, "name", "Alice"));
    return ctx;
  }
}
