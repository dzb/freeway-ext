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
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.RouteHandler;
import java.io.InputStream;
import java.io.OutputStream;
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
 * HealthFilter} &rarr; no-op route handler. Request timing is measured by {@code WebServer} itself
 * rather than a filter.
 *
 * <p>Uses a real {@link HttpContextImpl} (not a stub) so that filter overhead includes real
 * header/body/status operations. It therefore lives in the core {@code http.engine} package: a
 * reused pooled context is only reachable through the package-private {@link
 * HttpContextImpl#reset(String, String, String, String, String, String, String, String, Object[],
 * Object[])}.
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

  private HttpContextImpl normalCtx;
  private HttpContextImpl healthCtx;
  private HttpContextImpl corsCtx;
  private RouteHandler chain;

  @Setup
  public void setup() {
    var json = new JsonCodecDefault();
    var coercer = new CoercerDefault();

    // Real filter chain: cors -> health -> noop handler
    var cors = CorsFilter.defaults();
    var health = HealthFilter.defaults();
    RouteHandler noop = ctx -> {};
    RouteHandler h = ctx -> health.doFilter(ctx, noop);
    chain = ctx -> cors.doFilter(ctx, h);

    // Normal GET /ping — passes through all filters
    normalCtx = new HttpContextImpl(json, coercer);
    normalCtx.reset(
        "GET",
        "/ping",
        null,
        Map.of("Host", List.of("127.0.0.1")),
        InputStream.nullInputStream(),
        -1,
        false,
        OutputStream.nullOutputStream(),
        null,
        true);

    // Health check request — intercepted by HealthFilter
    healthCtx = new HttpContextImpl(json, coercer);
    healthCtx.reset(
        "GET",
        "/healthz",
        null,
        Map.of("Host", List.of("127.0.0.1")),
        InputStream.nullInputStream(),
        -1,
        false,
        OutputStream.nullOutputStream(),
        null,
        true);

    // CORS preflight — intercepted by CorsFilter
    corsCtx = new HttpContextImpl(json, coercer);
    corsCtx.reset(
        "OPTIONS",
        "/api/data",
        null,
        Map.ofEntries(
            Map.entry("Host", List.of("127.0.0.1")),
            Map.entry("Origin", List.of("https://example.com")),
            Map.entry("Access-Control-Request-Method", List.of("POST"))),
        InputStream.nullInputStream(),
        -1,
        false,
        OutputStream.nullOutputStream(),
        null,
        true);
  }

  /** Request passes through all filters to the handler. */
  @Benchmark
  public void normalRequest() throws Exception {
    chain.handle(normalCtx);
  }

  /** HealthFilter matches /healthz and short-circuits with 200 + JSON. */
  @Benchmark
  public void healthCheckMatch() throws Exception {
    chain.handle(healthCtx);
  }

  /** CorsFilter intercepts OPTIONS + Access-Control-Request-Method as preflight. */
  @Benchmark
  public void corsPreflight() throws Exception {
    chain.handle(corsCtx);
  }
}
