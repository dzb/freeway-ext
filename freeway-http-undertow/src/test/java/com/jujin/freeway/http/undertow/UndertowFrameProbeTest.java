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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UndertowFrameProbeTest {
  private AppRuntime app;

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
      app = null;
    }
    System.clearProperty("freeway.http.server.port");
    System.clearProperty("freeway.http.server.host");
    System.clearProperty("freeway.http.websocket.max-frame-size");
  }

  @Test
  void probeUndertowTextFrameFinBit() throws Exception {
    int port = freePort();
    System.setProperty("freeway.http.server.host", "127.0.0.1");
    System.setProperty("freeway.http.server.port", String.valueOf(port));

    app = FreewayApp.run(new String[0], new UndertowWebEngineModule(), new TestAppModule());
    assertTrue(app.get(WebServer.class).isRunning());

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      InputStream input = socket.getInputStream();
      OutputStream output = socket.getOutputStream();
      performHandshake(output, input, "/api/ws/lobby", port);
      sendTextFrame(output, "warmup");
      Frame first = readFrame(input);
      assertEquals(1, first.opcode(), "expected text frame");
      assertTrue(first.fin(), "expected FIN bit set");
      assertEquals(0, first.rsvBits(), "expected RSV bits clear");
      assertTrue(
          new String(first.payload(), StandardCharsets.UTF_8).startsWith("echo:warmup:lobby:"));
      sendTextFrame(output, "hello");
      Frame second = readFrame(input);
      assertEquals(1, second.opcode(), "expected text frame");
      assertTrue(second.fin(), "expected FIN bit set");
      assertEquals(0, second.rsvBits(), "expected RSV bits clear");
      assertTrue(
          new String(second.payload(), StandardCharsets.UTF_8).startsWith("echo:hello:lobby:"));
      sendCloseFrame(output, 1000, "bye");
      Frame closeFrame = readFrame(input);
      assertEquals(8, closeFrame.opcode(), "expected close frame");
      assertTrue(closeFrame.fin(), "expected FIN bit set");
      assertEquals(0, closeFrame.rsvBits(), "expected RSV bits clear");
    }
  }

  @Test
  void closesConnectionWhenMessageExceedsLimit() throws Exception {
    int port = freePort();
    System.setProperty("freeway.http.server.host", "127.0.0.1");
    System.setProperty("freeway.http.server.port", String.valueOf(port));
    System.setProperty("freeway.http.websocket.max-frame-size", "64");

    app = FreewayApp.run(new String[0], new UndertowWebEngineModule(), new TestAppModule());

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      InputStream input = socket.getInputStream();
      OutputStream output = socket.getOutputStream();
      performHandshake(output, input, "/api/ws/lobby", port);
      sendTextFrame(output, "x".repeat(100));
      // The 1009 close frame is written asynchronously right before the
      // channel closes, so the client may see the frame or a plain EOF.
      // Either is a rejection — the assertions below fail if an echo arrives.
      try {
        Frame closeFrame = readFrame(input);
        assertEquals(8, closeFrame.opcode(), "expected close frame");
        assertTrue(closeFrame.payload().length >= 2, "expected close frame to carry a status code");
        int code = ((closeFrame.payload()[0] & 0xFF) << 8) | (closeFrame.payload()[1] & 0xFF);
        assertEquals(1009, code, "expected 1009 message-too-big close code");
      } catch (IOException ex) {
        // Connection closed without a close frame (flush ordering).
      }
    }
  }

  static final class TestAppModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
      binder
          .contribute(WebSocketGroup.class)
          .add(
              WebSocketGroup.of(
                  "/api",
                  WebSocketRoute.of(
                      "/ws/{room}",
                      session ->
                          new WebSocketListener() {
                            @Override
                            public void onText(String text) throws Exception {
                              session.sendText(
                                  "echo:"
                                      + text
                                      + ":"
                                      + session.pathVar("room").orElse("")
                                      + ":"
                                      + session.correlationId());
                            }
                          })));
      binder.contribute(Route.class).add(Route.get("/ping", ctx -> ctx.send(200, "pong")));
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void performHandshake(
      OutputStream output, InputStream input, String path, int port) throws Exception {
    byte[] nonce = new byte[16];
    new SecureRandom().nextBytes(nonce);
    String key = Base64.getEncoder().encodeToString(nonce);
    String request =
        ""
            + "GET "
            + path
            + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1:"
            + port
            + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Sec-WebSocket-Key: "
            + key
            + "\r\n"
            + "\r\n";
    output.write(request.getBytes(StandardCharsets.US_ASCII));
    output.flush();
    String response = readHttpHeaders(input);
    assertTrue(response.startsWith("HTTP/1.1 101"), response);
    assertEquals(expectedAccept(key), headerValue(response, "Sec-WebSocket-Accept"));
    assertEquals(null, headerValue(response, "Sec-WebSocket-Extensions"));
  }

  private static void sendTextFrame(OutputStream output, String text) throws IOException {
    writeMaskedFrame(output, (byte) 0x1, text.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeMaskedFrame(OutputStream output, byte opcode, byte[] payload)
      throws IOException {
    byte[] mask = new byte[4];
    new SecureRandom().nextBytes(mask);
    output.write(0x80 | opcode);
    if (payload.length < 126) {
      output.write(0x80 | payload.length);
    } else if (payload.length < 65536) {
      output.write(0x80 | 126);
      output.write(payload.length >> 8);
      output.write(payload.length);
    } else {
      throw new IllegalArgumentException("payload too large for test frame");
    }
    output.write(mask);
    for (int i = 0; i < payload.length; i++) {
      output.write(payload[i] ^ mask[i % 4]);
    }
    output.flush();
  }

  private static void sendCloseFrame(OutputStream output, int code, String reason)
      throws IOException {
    byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(2 + reasonBytes.length);
    buffer.putShort((short) code);
    buffer.put(reasonBytes);
    writeMaskedFrame(output, (byte) 0x8, buffer.array());
  }

  private static String readHttpHeaders(InputStream input) throws IOException {
    StringBuilder builder = new StringBuilder();
    int matched = 0;
    while (true) {
      int value = input.read();
      if (value == -1) {
        throw new IOException("Unexpected EOF during websocket handshake");
      }
      builder.append((char) value);
      matched =
          switch (matched) {
            case 0 -> value == '\r' ? 1 : 0;
            case 1 -> value == '\n' ? 2 : 0;
            case 2 -> value == '\r' ? 3 : 0;
            case 3 -> value == '\n' ? 4 : 0;
            default -> 0;
          };
      if (matched == 4) {
        return builder.toString();
      }
    }
  }

  private static String headerValue(String response, String headerName) {
    for (String line : response.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      if (line.substring(0, colon).trim().equalsIgnoreCase(headerName)) {
        return line.substring(colon + 1).trim();
      }
    }
    return null;
  }

  private static String expectedAccept(String key) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    byte[] hash =
        digest.digest(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
    return Base64.getEncoder().encodeToString(hash);
  }

  private static Frame readFrame(InputStream input) throws IOException {
    int first = input.read();
    int second = input.read();
    if (first == -1 || second == -1) {
      throw new IOException("Unexpected EOF while reading websocket frame");
    }
    int opcode = first & 0x0F;
    boolean fin = (first & 0x80) != 0;
    int rsvBits = first & 0x70;
    int length = second & 0x7F;
    if (length == 126) {
      length = (input.read() << 8) | input.read();
    } else if (length == 127) {
      long extended = 0;
      for (int i = 0; i < 8; i++) {
        extended = (extended << 8) | input.read();
      }
      if (extended > 1024 * 1024) {
        throw new IOException("Frame too large: " + extended);
      }
      length = (int) extended;
    }
    byte[] mask = (second & 0x80) != 0 ? input.readNBytes(4) : new byte[0];
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) {
      throw new IOException("Unexpected EOF while reading websocket payload");
    }
    if ((second & 0x80) != 0) {
      for (int i = 0; i < payload.length; i++) {
        payload[i] = (byte) (payload[i] ^ mask[i % 4]);
      }
    }
    return new Frame(fin, opcode, rsvBits, payload);
  }

  private record Frame(boolean fin, int opcode, int rsvBits, byte[] payload) {}
}
