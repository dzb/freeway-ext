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

package com.jujin.freeway.http.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The seam's upgrade consultation, pinned from the outside: an engine must ask {@code
 * ExchangeHandler.websocket(method, path, origin)} for every upgrade-eligible request, so a path no
 * route declared and an origin the CORS list refuses must both abort the handshake. The adapter has
 * no route table of its own, so a rejection it cannot reach alone is proof it asked; the positive
 * control (a declared path upgrades) keeps a broken handshake helper from passing the negatives
 * vacuously.
 */
public abstract class WebSocketUpgradeContract extends EngineFixture {

  /** The only origin this contract's CORS list admits. */
  private static final String ALLOWED_ORIGIN = "https://good.example";

  private static final CorsFilter CORS =
      CorsFilter.defaults().withEnabled(true).withAllowedOrigins(List.of(ALLOWED_ORIGIN));

  @Test
  void declaredPathUpgradesThroughTheSeam() throws Exception {
    try (var server = start(pipeline(), defaultConfig(), CORS)) {
      assertEquals(
          101,
          handshake(server.port(), "/api/ws", null),
          engineName() + " upgrades a declared path");
    }
  }

  @Test
  void undeclaredPathNeverUpgrades() throws Exception {
    try (var server = start(pipeline(), defaultConfig(), CORS)) {
      int status = handshake(server.port(), "/api/no-such-ws", null);
      assertNotEquals(101, status, engineName() + " must not upgrade a path no route declared");
    }
  }

  @Test
  void originOutsideTheCorsListNeverUpgrades() throws Exception {
    try (var server = start(pipeline(), defaultConfig(), CORS)) {
      int status = handshake(server.port(), "/api/ws", "https://evil.example");
      assertNotEquals(101, status, engineName() + " must abort when the seam refuses the origin");
    }
  }

  private static Pipelines pipeline() {
    return Pipelines.of(
        List.of(),
        List.of(
            WebSocketGroup.of(
                "/api", WebSocketRoute.of("/ws", session -> new WebSocketListener() {}))));
  }

  /**
   * One raw upgrade attempt; returns the status-line code, or 0 when the server answered nothing
   * (EOF or timeout — a rejection by connection close). A positive control asserting 101 keeps this
   * helper honest.
   */
  private static int handshake(int port, String path, String origin) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      byte[] nonce = new byte[16];
      new SecureRandom().nextBytes(nonce);
      String key = Base64.getEncoder().encodeToString(nonce);
      StringBuilder req = new StringBuilder();
      req.append("GET ").append(path).append(" HTTP/1.1\r\n");
      req.append("Host: 127.0.0.1:").append(port).append("\r\n");
      req.append("Upgrade: websocket\r\n");
      req.append("Connection: Upgrade\r\n");
      req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
      req.append("Sec-WebSocket-Version: 13\r\n");
      if (origin != null) {
        req.append("Origin: ").append(origin).append("\r\n");
      }
      req.append("\r\n");
      out.write(req.toString().getBytes(StandardCharsets.UTF_8));
      out.flush();
      return readStatus(socket.getInputStream());
    }
  }

  /** The code from the response's status line; 0 when no line arrives. */
  private static int readStatus(InputStream in) {
    try {
      StringBuilder line = new StringBuilder();
      int prev = 0;
      while (true) {
        int b = in.read();
        if (b < 0) {
          return 0;
        }
        if (b == '\n' && prev == '\r') {
          break;
        }
        line.append((char) b);
        prev = b;
      }
      String[] parts = line.toString().trim().split(" ");
      if (parts.length >= 2 && parts[1].matches("\\d{3}")) {
        return Integer.parseInt(parts[1]);
      }
      return 0;
    } catch (Exception ex) {
      return 0;
    }
  }
}
