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

package com.jujin.freeway.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.harness.ServerHarness.Engine;
import com.jujin.freeway.bench.harness.ServerHarness.Scenario;
import com.jujin.freeway.http.HttpServerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The harness must not measure one engine while labelling it as another. */
class ServerHarnessTest {

  @Test
  void bareEngineServesAndASecondProviderIsRefused() throws Exception {
    // com.sun.net.httpserver.HttpServer resolves its provider once per JVM and
    // caches it in a static field, so a second, different bare engine would
    // silently report the first engine's numbers under the second engine's
    // name. The first engine still has to work — and be reachable.
    try (var harness = ServerHarness.start(Engine.JDK_NATIVE, Scenario.PING)) {
      assertTrue(harness.port() > 0, "the bare server is listening");

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> ServerHarness.start(Engine.ROBAHO_NATIVE, Scenario.PING));
      assertTrue(
          failure.getMessage().contains("fixed once per JVM"),
          "the failure explains why: " + failure.getMessage());
      assertTrue(
          failure.getMessage().contains("robaho-native"),
          "and names the refused engine: " + failure.getMessage());
    }
  }

  @Test
  void freewayEngineAnswersThePingScenario() throws Exception {
    // The three Freeway-based engines share one assembly path; this is the
    // smoke check that the shared path still serves traffic.
    try (var harness = ServerHarness.start(Engine.FREEWAY, Scenario.PING)) {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + harness.port() + "/ping"))
                  .GET()
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("pong", response.body());
    }
  }

  @Test
  void everyEnginesFixedBodyMatchesItsScenarioSpec() throws Exception {
    // The spec is the single source for the path, method, content type and expected bytes; this
    // pins each HTTP engine's real answer against it, so a drift shows up here instead of as
    // "engine 100% errors" during a measurement.
    for (Scenario scenario : new Scenario[] {Scenario.PING, Scenario.JSON}) {
      var spec = ScenarioSpec.of(scenario);
      for (Engine engine : new Engine[] {Engine.FREEWAY, Engine.JDK_NATIVE}) {
        try (var harness = ServerHarness.start(engine, scenario)) {
          var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
          var response =
              client.send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + harness.port() + spec.path()))
                      .GET()
                      .timeout(Duration.ofSeconds(10))
                      .build(),
                  HttpResponse.BodyHandlers.ofByteArray());
          assertEquals(200, response.statusCode(), engine + " " + scenario);
          assertArrayEquals(
              spec.responseBody(),
              response.body(),
              engine + " must answer " + scenario + "'s spec");
          if (spec.contentType() != null) {
            assertTrue(
                response
                    .headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .startsWith(spec.contentType()),
                engine + " " + scenario + " content type: " + response.headers().map());
          }
        }
      }
    }
  }

  @Test
  void everyEngineEchoesTheRequestBodyBack() throws Exception {
    // The echo scenario is the one dynamic body: it must read the request body on every engine
    // (an unconditional blocking read on a GET is what broke Undertow's other scenarios once).
    byte[] payload = "freeway-echo".getBytes(StandardCharsets.UTF_8);
    for (Engine engine :
        new Engine[] {
          Engine.FREEWAY, Engine.JDK_NATIVE, Engine.UNDERTOW_NATIVE, Engine.JETTY_NATIVE
        }) {
      var spec = ScenarioSpec.of(Scenario.ECHO_BODY);
      try (var harness = ServerHarness.start(engine, Scenario.ECHO_BODY)) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + harness.port() + spec.path()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .timeout(Duration.ofSeconds(10))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), engine + " echo status");
        assertArrayEquals(payload, response.body(), engine + " must echo the request body");
      }
    }
  }

  @Test
  void freewayPipelineMapsOversizedBodiesLikeProduction() throws Exception {
    // The harness assembles through HttpModule, which consults core's default error
    // handler last. Without it an oversized body would be measured as
    // an unmapped failure (dropped connection) instead of the 413 a real
    // application returns — the whole reason the harness does not hand-roll the
    // pipeline.
    try (var harness = ServerHarness.start(Engine.FREEWAY, Scenario.ECHO_BODY)) {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      byte[] oversized = new byte[(int) HttpServerConfig.DEFAULT_MAX_BODY_SIZE + 4096];
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + harness.port() + "/echo"))
                  .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                  .timeout(Duration.ofSeconds(20))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, response.statusCode(), response.body());
    }
  }
}
