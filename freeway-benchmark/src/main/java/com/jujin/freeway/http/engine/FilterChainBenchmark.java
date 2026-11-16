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

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpExchangeSupport;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.RouteHandler;
import java.io.InputStream;
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
 * JMH benchmark for the real Freeway HTTP filter chain.
 *
 * <p>Builds the same filter chain used in production: {@link CorsFilter} &rarr; {@link
 * HealthFilter} &rarr; no-op route handler, running against a real {@link HttpExchangeSupport} over
 * {@link BenchTransport} so the measured cost includes real header/body/status work without a
 * socket.
 *
 * <p>One exchange is created per operation, as an engine does: the support object is per request
 * and is discarded with it, so a benchmark that reused one would measure a state machine nothing in
 * production has.
 *
 * <p>Three request shapes exercise different filter code paths:
 *
 * <ul>
 *   <li>{@link #normalRequest()} — passes through all filters to the handler
 *   <li>{@link #healthCheckMatch()} — intercepted by HealthFilter before routing
 *   <li>{@link #corsPreflight()} — intercepted by CorsFilter as a CORS preflight
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class FilterChainBenchmark {

  private static final JsonCodecDefault JSON = new JsonCodecDefault();
  private static final CoercerDefault COERCER = new CoercerDefault();

  private RouteHandler chain;

  @Setup
  public void setup() {
    // Real filter chain: cors -> health -> noop handler
    var cors = CorsFilter.defaults();
    var health = HealthFilter.defaults();
    RouteHandler noop = ctx -> {};
    RouteHandler h = ctx -> health.doFilter(ctx, noop);
    chain = ctx -> cors.doFilter(ctx, h);
  }

  private static HttpExchangeSupport exchange(
      String method, String path, Map<String, List<String>> headers) {
    return new HttpExchangeSupport(
        new BenchTransport(method, path, null, headers, InputStream.nullInputStream()),
        JSON,
        COERCER,
        HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
        HttpServerConfig.CompressionConfig.DEFAULT,
        null);
  }

  /** Request passes through all filters to the handler. */
  @Benchmark
  public void normalRequest() throws Exception {
    chain.handle(exchange("GET", "/ping", Map.of("Host", List.of("127.0.0.1"))));
  }

  /** HealthFilter matches /healthz and short-circuits with 200 + JSON. */
  @Benchmark
  public void healthCheckMatch() throws Exception {
    chain.handle(exchange("GET", "/healthz", Map.of("Host", List.of("127.0.0.1"))));
  }

  /** CorsFilter intercepts OPTIONS + Access-Control-Request-Method as preflight. */
  @Benchmark
  public void corsPreflight() throws Exception {
    chain.handle(
        exchange(
            "OPTIONS",
            "/api/data",
            Map.ofEntries(
                Map.entry("Host", List.of("127.0.0.1")),
                Map.entry("Origin", List.of("https://example.com")),
                Map.entry("Access-Control-Request-Method", List.of("POST")))));
  }
}
