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

import java.nio.charset.StandardCharsets;

/**
 * What a benchmark scenario is, on the wire: the request that drives it, the response every engine
 * must answer with, and whether it upgrades to WebSocket. Every server implementation in {@link
 * ServerHarness} and the client's request pattern read this one declaration, so adding a scenario
 * is one entry here instead of one case in each engine's handler.
 *
 * @param scenario the scenario this spec describes
 * @param method the HTTP method the client sends (and the only one the server answers)
 * @param path the request path
 * @param contentType the response {@code Content-Type}, or null when the engine's default applies
 * @param responseBody the fixed response body, or null when the response is dynamic (echo, WS)
 * @param echoBody true when the response body is the request body
 * @param json true when the response body is JSON, so the Freeway route serializes it through the
 *     codec (measuring serialization) while the byte-level engines write {@code responseBody}
 * @param webSocket true when the scenario upgrades instead of answering an HTTP exchange
 */
public record ScenarioSpec(
    ServerHarness.Scenario scenario,
    String method,
    String path,
    String contentType,
    byte[] responseBody,
    boolean echoBody,
    boolean json,
    boolean webSocket) {

  /** The one table: request shape, response shape and upgrade flag per scenario. */
  public static ScenarioSpec of(ServerHarness.Scenario scenario) {
    return switch (scenario) {
      case PING ->
          new ScenarioSpec(
              scenario,
              "GET",
              "/ping",
              "text/plain",
              "pong".getBytes(StandardCharsets.ISO_8859_1),
              false,
              false,
              false);
      case JSON ->
          new ScenarioSpec(
              scenario,
              "GET",
              "/api/resource",
              "application/json",
              "{\"id\":1,\"name\":\"test\"}".getBytes(StandardCharsets.ISO_8859_1),
              false,
              true,
              false);
      case ECHO_BODY ->
          new ScenarioSpec(
              scenario, "POST", "/echo", "application/octet-stream", null, true, false, false);
      case WS_ECHO -> new ScenarioSpec(scenario, "GET", "/ws/echo", null, null, false, false, true);
    };
  }

  /** The response body for a request that carried {@code requestBody}. */
  public byte[] responseFor(byte[] requestBody) {
    return echoBody ? requestBody : responseBody;
  }
}
