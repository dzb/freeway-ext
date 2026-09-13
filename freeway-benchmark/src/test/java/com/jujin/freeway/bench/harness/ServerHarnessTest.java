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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.harness.ServerHarness.Engine;
import com.jujin.freeway.bench.harness.ServerHarness.Scenario;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
}
