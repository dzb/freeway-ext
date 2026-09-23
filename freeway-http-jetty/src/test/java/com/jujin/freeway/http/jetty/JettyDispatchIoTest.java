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
import com.jujin.freeway.http.HttpServerHandle;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.testkit.Pipelines;
import com.jujin.freeway.http.testkit.Symbols;
import com.jujin.freeway.http.testkit.TestServers;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.Test;

/**
 * The {@code freeway.http.jetty.dispatch-io} knob: by default the root handler stays BLOCKING and
 * every request is dispatched from the connection producer thread to the pool; {@code false}
 * declares it NON_BLOCKING so the handler runs inline on the producer.
 */
class JettyDispatchIoTest {

  private static final HttpServerConfig CONFIG =
      HttpServerConfig.defaults()
          .withPort(0)
          .withBacklog(64)
          .withShutdownGrace(Duration.ofSeconds(5));

  private static JettyHttpEngine engine(Map<String, String> symbols) {
    return new JettyHttpEngine(new JsonCodecDefault(), new CoercerDefault(), Symbols.of(symbols));
  }

  @Test
  void onlyFalseOptsOutOfDispatch() {
    assertEquals(Invocable.InvocationType.NON_BLOCKING, JettyHttpEngine.invocationType("false"));
    assertEquals(Invocable.InvocationType.NON_BLOCKING, JettyHttpEngine.invocationType("FALSE"));
    assertEquals(Invocable.InvocationType.BLOCKING, JettyHttpEngine.invocationType("true"));
    // A malformed value keeps the safe default, as the Undertow knob does.
    assertEquals(Invocable.InvocationType.BLOCKING, JettyHttpEngine.invocationType("banana"));
    assertEquals(Invocable.InvocationType.BLOCKING, JettyHttpEngine.invocationType(null));
  }

  private static Invocable.InvocationType installedInvocationType(HttpServerHandle handle)
      throws Exception {
    Field serverField = handle.getClass().getDeclaredField("server");
    serverField.setAccessible(true);
    Server server = (Server) serverField.get(handle);
    Handler handler = server.getHandler();
    // Propagation through GracefulHandler is the point: a wrapper that failed to delegate the
    // invocation type would silently make the knob a no-op.
    return handler.getInvocationType();
  }

  @Test
  void knobFalseInstallsNonBlockingHandlerThroughGracefulWrapper() throws Exception {
    var engine = engine(Map.of("freeway.http.jetty.dispatch-io", "false"));
    try (var handle = engine.start(CONFIG, ctx -> {})) {
      assertEquals(Invocable.InvocationType.NON_BLOCKING, installedInvocationType(handle));
    }
  }

  @Test
  void defaultInstallsBlockingHandler() throws Exception {
    var engine = engine(Map.of());
    try (var handle = engine.start(CONFIG, ctx -> {})) {
      assertEquals(Invocable.InvocationType.BLOCKING, installedInvocationType(handle));
    }
  }

  @Test
  void optOutStillServesPingAndBodyEcho() throws Exception {
    // The measured win only means anything if the opt-out shape serves the normal pipeline
    // unchanged, including a blocking body read (which works while the producer happens to be a
    // pool thread).
    var engine = engine(Map.of("freeway.http.jetty.dispatch-io", "false"));
    var routes =
        List.of(
            Route.get("/ping", ctx -> ctx.send(200, "pong")),
            Route.post("/echo", ctx -> ctx.output(ctx.body())));
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    try (var server = TestServers.start(engine, CONFIG, Pipelines.of(routes))) {
      server.start();
      var ping =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/ping"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, ping.statusCode());
      assertEquals("pong", ping.body());
      var echo =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/echo"))
                  .POST(HttpRequest.BodyPublishers.ofString("hi"))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, echo.statusCode());
      assertEquals("hi", echo.body());
      assertTrue(ping.headers().firstValue("X-Request-Id").isPresent());
    }
  }
}
