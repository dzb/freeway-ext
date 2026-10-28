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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.http.route.Route;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The inbound header budget is one configured number ({@code HttpServerConfig.maxHeaderBytes}), not
 * a per-engine constant: a request the built-in engine refuses must not be accepted by an adapter,
 * and vice versa.
 *
 * <p>Both directions matter. Only rejecting proves a lower limit was wired in; only accepting
 * proves the limit did not become a hard-coded ceiling smaller than the configured one (the shape
 * this contract was added to catch: two engines with their own 8 KiB literals while the core
 * default is 16 KiB).
 */
public abstract class HeaderBudgetContract extends EngineFixture {

  private static final String PADDING = "X-Padding";

  @Test
  void aHeaderBlockOverTheConfiguredBudgetIsRejectedWith4xx() throws Exception {
    var config = defaultConfig().withMaxHeaderBytes(1024);
    try (var server = start(Pipelines.of(ping()), config)) {
      var response = get(server, "a".repeat(4096));
      assertTrue(
          response.statusCode() >= 400 && response.statusCode() < 500,
          engineName()
              + " must refuse a header block past maxHeaderBytes=1024 with a 4xx, got "
              + response.statusCode());
    }
  }

  @Test
  void aHeaderBlockInsideTheConfiguredBudgetIsAccepted() throws Exception {
    // 4 KiB is a normal request inside a 16 KiB budget; a hard-coded 1 KiB or
    // 8 KiB ceiling would fail this while the framework claims 16 KiB.
    var config = defaultConfig().withMaxHeaderBytes(16 * 1024);
    try (var server = start(Pipelines.of(ping()), config)) {
      var response = get(server, "a".repeat(4096));
      assertEquals(
          200,
          response.statusCode(),
          engineName() + " must accept a 4 KiB header inside a 16 KiB budget");
    }
  }

  private static HttpResponse<String> get(TestServers.TestServer server, String padding)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(uri(server, "/ping")).header(PADDING, padding).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static List<Route> ping() {
    return List.of(Route.get("/ping", ctx -> ctx.send(200, "pong")));
  }
}
