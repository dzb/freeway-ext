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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.testkit.Pipelines;
import com.jujin.freeway.http.testkit.TestServers;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two socket-level knobs that mean the same thing in every engine: {@code read-timeout=0} means
 * "no deadline" (not "already expired"), and the header budget is the configured {@code
 * maxHeaderBytes} (not an engine constant — the 8 KiB literal this used to assert is how the field
 * went unwired).
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

        String statusLine = readStatusLine(socket.getInputStream());
        assertTrue(
            statusLine.contains(" 200"),
            "a slow header must still be served with read-timeout=0, got: " + statusLine);
      }
    }
  }

  @Test
  void headerBudgetMatchesTheBuiltInEngine() throws Exception {
    // The budget is configuration, not an engine constant: 12 KiB is past an
    // 8 KiB budget and must be refused, 4 KiB inside the same budget is served.
    // (The old form asserted a hard-coded 8 KiB ceiling while the core default
    // had moved to 16 KiB — which is exactly how the field went unwired.)
    try (var server =
        start(
            HttpServerConfig.defaults()
                .withHost("127.0.0.1")
                .withPort(0)
                .withMaxHeaderBytes(8 * 1024))) {
      String oversized = "X-Big: " + "a".repeat(12 * 1024) + "\r\n";
      String rejected = request(server.port(), oversized);
      assertFalse(rejected.contains(" 200"), "an oversized header must not be served: " + rejected);

      String ok = request(server.port(), "X-Small: " + "b".repeat(4 * 1024) + "\r\n");
      assertTrue(ok.contains(" 200"), "a header inside the budget must be served: " + ok);
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static TestServers.TestServer start(HttpServerConfig config) {
    var engine = new UndertowHttpEngine(new JsonCodecDefault(), new CoercerDefault());
    return TestServers.start(engine, config, pipeline());
  }

  private static String request(int port, String extraHeaders) throws Exception {
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      var out = socket.getOutputStream();
      out.write(
          ("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\n" + extraHeaders + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.flush();
      return readStatusLine(socket.getInputStream());
    }
  }

  /**
   * The status line only: a served request keeps its connection open, so reading to EOF would wait
   * for the idle timeout.
   */
  private static String readStatusLine(InputStream in) throws Exception {
    var line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1 && b != '\n') {
      line.write(b);
    }
    return line.toString(StandardCharsets.UTF_8).trim();
  }

  private static Pipelines pipeline() {
    var routes = List.of(Route.get("/ping", ctx -> ctx.send(200, "pong")));
    return Pipelines.of(routes);
  }
}
