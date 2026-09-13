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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * JMH benchmark for the HTTP/1.1 request parser.
 *
 * <p>Lives in the core {@code http.engine} package on purpose: {@link Http1xParser} and its {@code
 * ParsedRequest} are package-private, and parsing the real parser is the point of the benchmark.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class Http1xParserBenchmark {

  private byte[] request;
  private Http1xParser parser;

  @Setup
  public void setup() {
    request =
        ("GET /users/42?active=true&limit=10 HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "User-Agent: freeway-bench\r\n"
                + "Accept: */*\r\n"
                + "Connection: keep-alive\r\n"
                + "X-Trace-Id: trace-1\r\n"
                + "\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    parser = new Http1xParser(new ByteArrayInputStream(request));
  }

  @Benchmark
  public Http1xParser.ParsedRequest parse() throws IOException {
    parser.reset(new ByteArrayInputStream(request));
    return parser.parse();
  }
}
