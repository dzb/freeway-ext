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

package com.jujin.freeway.http.jetty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.testkit.EngineFixture;
import com.jujin.freeway.http.testkit.Pipelines;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The transport fields the Jetty adapter claims to honor: the honor contract pins each configured
 * field as either applied at startup or reported at startup — this observes the applied half
 * behaviorally for maxConnections (the reported half is the startup warning for writeTimeout, the
 * one field Jetty cannot map).
 *
 * <p>Jetty's own mechanism ({@code NetworkConnectionLimit}) enforces the cap by <em>stopping
 * acceptance</em> while the limit is reached, so an excess connection waits in the kernel backlog
 * instead of being closed on the spot as the built-in engine closes it. The contract is the
 * semantic, not the mechanism: nothing beyond the cap is ever served, and connections are admitted
 * again as soon as a slot frees.
 */
class JettyTransportContractTest extends EngineFixture {

  @Override
  protected HttpEngine newEngine() {
    return new JettyWebEngine(jsonCodec(), coercer());
  }

  @Override
  protected String engineName() {
    return "jetty";
  }

  @Test
  void maxConnectionsCapsConcurrencyAndReleasesWhenASlotFrees() throws Exception {
    var config = defaultConfig().withMaxConnections(2).withReadTimeout(Duration.ofMinutes(2));
    try (var server =
        start(Pipelines.of(List.of(Route.get("/", ctx -> ctx.send(200, "ok")))), config)) {
      try (Socket first = new Socket("127.0.0.1", server.port());
          Socket second = new Socket("127.0.0.1", server.port())) {
        Thread.sleep(200); // let both sessions register with the connector

        // Beyond the cap while both slots are held: no response arrives — a
        // closed socket (built-in style) and one waiting in the backlog both
        // read as "nothing served".
        try (Socket third = new Socket("127.0.0.1", server.port())) {
          third.setSoTimeout(1500);
          sendGet(third, server.port());
          int firstByte = -1;
          try {
            firstByte = third.getInputStream().read();
          } catch (SocketTimeoutException held) {
            firstByte = -1;
          }
          assertEquals(
              -1, firstByte, engineName() + " must not serve a connection beyond maxConnections");
        }

        // Free a slot: acceptance resumes and a fresh connection is served —
        // the cap held connections back, it did not break the server.
        first.close();
        Thread.sleep(200);
        try (Socket fourth = new Socket("127.0.0.1", server.port())) {
          fourth.setSoTimeout(5000);
          sendGet(fourth, server.port());
          assertEquals(200, readStatus(fourth), engineName() + " must serve once a slot frees");
        }
      }
    }
  }

  private static void sendGet(Socket socket, int port) throws Exception {
    socket
        .getOutputStream()
        .write(
            ("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
    socket.getOutputStream().flush();
  }

  /** The code from the response's status line; -1 when the server answers nothing. */
  private static int readStatus(Socket socket) throws Exception {
    StringBuilder line = new StringBuilder();
    int prev = 0;
    while (true) {
      int b = socket.getInputStream().read();
      if (b < 0) {
        return -1;
      }
      if (b == '\n' && prev == '\r') {
        break;
      }
      line.append((char) b);
      prev = b;
    }
    String[] parts = line.toString().trim().split(" ");
    return parts.length >= 2 && parts[1].matches("\\d{3}") ? Integer.parseInt(parts[1]) : -1;
  }
}
