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
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * JMH benchmark for {@link HttpExchangeSupport} lookup methods.
 *
 * <p>The exchange is real (the shared policy object every engine composes) and is populated with
 * realistic request headers and query parameters, so the measured lookups are the ones handlers
 * actually run — the O(1)-then-O(n) header strategy included. Construction happens in setup: the
 * lookup cost is what this benchmark varies, not allocation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class HttpContextLookupBenchmark {

  private static final Map<String, List<String>> REQUEST_HEADERS =
      Map.ofEntries(
          Map.entry("x-trace-id", List.of("trace-1")),
          Map.entry("accept", List.of("text/plain")),
          Map.entry("content-type", List.of("application/json")),
          Map.entry("cache-control", List.of("no-cache")),
          Map.entry("user-agent", List.of("freeway-bench")),
          Map.entry("x-request-id", List.of("req-1")),
          Map.entry("x-forwarded-for", List.of("127.0.0.1")),
          Map.entry("authorization", List.of("Bearer token")));

  private HttpExchangeSupport ctx;

  @Setup
  public void setup() {
    ctx =
        new HttpExchangeSupport(
            new BenchTransport(
                "GET",
                "/users/42",
                Map.of("page", List.of("3"), "q", List.of("freeway")),
                REQUEST_HEADERS,
                InputStream.nullInputStream()),
            new JsonCodecDefault(),
            new CoercerDefault(),
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
            HttpServerConfig.CompressionConfig.DEFAULT,
            null);
    ctx.setPathVars(Map.of("id", "42"));
  }

  /** O(1) exact-match query param lookup. */
  @Benchmark
  public Optional<String> queryParam() {
    return ctx.queryParam("page");
  }

  /** O(1) exact-match header lookup (names are lowercase in the snapshot). */
  @Benchmark
  public Optional<String> headerExactMatch() {
    return ctx.header("x-trace-id");
  }

  /** Exact miss, then the case-insensitive fallback scan. */
  @Benchmark
  public Optional<String> headerCaseInsensitive() {
    return ctx.header("X-Trace-Id");
  }

  /** Full miss: O(1) miss + O(n) case-insensitive scan. */
  @Benchmark
  public Optional<String> headerMiss() {
    return ctx.header("x-missing-header");
  }

  /** queryParam fallback to pathVar via {@code HttpRequest#param(String)}. */
  @Benchmark
  public Optional<String> paramLookup() {
    return ctx.param("id");
  }
}
