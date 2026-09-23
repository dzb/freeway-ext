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

package com.jujin.freeway.http.undertow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.testkit.Pipelines;
import com.jujin.freeway.http.testkit.TestServers;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two socket-level knobs that mean the same thing in every engine: {@code read-timeout=0} means
 * "no deadline" (not "already expired"), and the header budget is the same 8 KiB the built-in
 * engine's HTTP/1 parser allows.
 */
class UndertowTransportLimitsTest {

  @Test
  void zeroReadTimeoutMeansNoDeadlineForSlowHeaders() throws Exception {
    // Undertow reads 0 as "expire now" for the request-parse timeout, so the
    // shared "0 disables" contract has to be translated to Undertow's spelling
    // of disabled (-1) — otherwise a segmented or slow request header is
    // dropped mid-request.
    try (var server =
        start(
            HttpServerConfig.defaults()
                .withHost("127.0.0.1")
                .withPort(0)
                .withReadTimeout(Duration.ZERO))) {
      try (var socket = new Socket("127.0.0.1", server.port())) {
        var out = socket.getOutputStream();
        // Headers split across two writes with a pause in between.
        out.write("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        Thread.sleep(400);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        String response = readAll(socket.getInputStream());
        assertTrue(
            response.startsWith("HTTP/1.1 200"),
            "a slow header must still be served with read-timeout=0, got: " + firstLine(response));
      }
    }
  }

  @Test
  void headerBudgetMatchesTheBuiltInEngine() throws Exception {
    try (var server = start(HttpServerConfig.defaults().withHost("127.0.0.1").withPort(0))) {
      // 12 KiB of headers: past the built-in engine's 8 KiB parser budget.
      String oversized = "X-Big: " + "a".repeat(12 * 1024) + "\r\n";
      String response = request(server.port(), oversized);
      assertTrue(
          !response.startsWith("HTTP/1.1 200"),
          "an oversized header must not be served, got: " + firstLine(response));

      // A request well inside the budget is unaffected.
      String ok = request(server.port(), "X-Small: value\r\n");
      assertTrue(ok.startsWith("HTTP/1.1 200"), "the normal request still works: " + firstLine(ok));
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static TestServers.TestServer start(HttpServerConfig config) {
    var engine = new UndertowHttpEngine(new JsonCodecDefault(), new CoercerDefault());
    return TestServers.start(engine, config, pipeline());
  }

  private static String request(int port, String extraHeaders) throws Exception {
    try (var socket = new Socket("127.0.0.1", port)) {
      var out = socket.getOutputStream();
      out.write(
          ("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\n" + extraHeaders + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.flush();
      return readAll(socket.getInputStream());
    }
  }

  private static String readAll(InputStream in) throws Exception {
    byte[] bytes = in.readAllBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String firstLine(String response) {
    int end = response.indexOf('\n');
    return end < 0 ? response : response.substring(0, end).trim();
  }

  private static Pipelines pipeline() {
    var routes = List.of(Route.get("/ping", ctx -> ctx.send(200, "pong")));
    return Pipelines.of(routes);
  }
}
