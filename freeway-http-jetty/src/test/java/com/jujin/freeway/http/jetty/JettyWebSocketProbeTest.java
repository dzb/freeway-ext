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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestPipeline;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Raw-socket WebSocket contract probe for the Jetty adapter. */
class JettyWebSocketProbeTest {

  @Test
  void probeJettyTextFrameEcho() throws Exception {
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var wsGroup =
        WebSocketGroup.of(
            "/api",
            WebSocketRoute.of(
                "/ws/{room}",
                session ->
                    new WebSocketListener() {
                      @Override
                      public void onText(String text) throws Exception {
                        session.sendText("echo:" + text + ":" + session.pathVar("room"));
                      }
                    }));
    var pipeline =
        new RequestPipeline(
            new RouteIndex(List.of(), List.of()),
            new WebSocketIndex(List.of(), List.of(wsGroup)),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            List.of());

    try (var server = new WebServer(engine, config, event -> {}, pipeline)) {
      server.start();
      try (Socket socket = new Socket("127.0.0.1", server.port())) {
        socket.setSoTimeout(5000);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        performHandshake(output, input, "/api/ws/lobby", server.port());
        sendTextFrame(output, "warmup");
        Frame first = readFrame(input);
        assertEquals(1, first.opcode(), "expected text frame");
        assertTrue(first.fin(), "expected FIN bit set");
        assertEquals(0, first.rsvBits(), "expected RSV bits clear");
        assertTrue(
            new String(first.payload(), StandardCharsets.UTF_8).startsWith("echo:warmup:lobby"));
        sendTextFrame(output, "hello");
        Frame second = readFrame(input);
        assertEquals(1, second.opcode(), "expected text frame");
        assertTrue(second.fin(), "expected FIN bit set");
        assertEquals(0, second.rsvBits(), "expected RSV bits clear");
        assertTrue(
            new String(second.payload(), StandardCharsets.UTF_8).startsWith("echo:hello:lobby"));
        sendCloseFrame(output, 1000, "bye");
        Frame closeFrame = readFrame(input);
        assertEquals(8, closeFrame.opcode(), "expected close frame");
        assertTrue(closeFrame.fin(), "expected FIN bit set");
        assertEquals(0, closeFrame.rsvBits(), "expected RSV bits clear");
      }
    }
  }

  @Test
  void rejectsOversizedMessageWithClose() throws Exception {
    var engine = new JettyWebEngine(new JsonCodecDefault(), new CoercerDefault());
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    var wsGroup =
        WebSocketGroup.of(
            "/api",
            WebSocketRoute.of(
                "/ws/{room}",
                session ->
                    new WebSocketListener() {
                      @Override
                      public void onText(String text) throws Exception {
                        session.sendText("echo:" + text + ":" + session.pathVar("room"));
                      }
                    }));
    var pipeline =
        new RequestPipeline(
            new RouteIndex(List.of(), List.of()),
            new WebSocketIndex(List.of(), List.of(wsGroup)),
            new CorsFilter(false, null, null, null, null, null, false),
            new HealthFilter(false, "/no-health", null),
            List.of(),
            List.of(),
            List.of());

    try (var server = new WebServer(engine, config, event -> {}, pipeline)) {
      server.start();
      try (Socket socket = new Socket("127.0.0.1", server.port())) {
        socket.setSoTimeout(5000);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        performHandshake(output, input, "/api/ws/lobby", server.port());
        // Default limit is 64 KiB; 70 KiB exceeds it. Jetty may close the
        // connection as soon as it reads the oversized frame header (a reset
        // mid-payload-write or EOF on read) or answer with a 1009 close frame;
        // either rejection is acceptable — what must never happen is an echo.
        try {
          sendTextFrame(output, "x".repeat(70 * 1024));
          Frame frame = readFrame(input);
          if (frame.opcode() != 8) {
            throw new AssertionError(
                "expected connection close for oversized message, got opcode " + frame.opcode());
          }
          assertTrue(frame.payload().length >= 2, "expected close frame to carry a status code");
          int code = ((frame.payload()[0] & 0xFF) << 8) | (frame.payload()[1] & 0xFF);
          assertEquals(1009, code, "expected 1009 message-too-big close code");
        } catch (IOException ex) {
          // Connection closed by the server without a close frame.
        }
      }
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
      output.write(0x80 | 127);
      long length = payload.length;
      for (int i = 7; i >= 0; i--) {
        output.write((int) (length >> (8 * i)));
      }
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
