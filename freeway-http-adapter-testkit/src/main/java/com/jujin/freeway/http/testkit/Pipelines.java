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

import com.jujin.freeway.http.RequestComponents;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.util.List;

/**
 * The pipeline every contract test starts from: the routes under test, everything else disabled.
 */
public final class Pipelines {

  private Pipelines() {}

  public static RequestComponents of(List<Route> routes) {
    return new RequestComponents(
        new RouteIndex(routes, List.of()),
        new WebSocketIndex(List.of(), List.of()),
        disabledCors(),
        disabledHealth(),
        List.of(),
        List.of(),
        List.of());
  }

  /**
   * CORS disabled: the contract tests send no {@code Origin}, so an active filter is pure noise.
   */
  public static CorsFilter disabledCors() {
    return new CorsFilter(false, null, null, null, null, null, false);
  }

  /** Health disabled: no contract probes it, and every engine must run the same pipeline. */
  public static HealthFilter disabledHealth() {
    return new HealthFilter(false, "/no-health", null);
  }
}
