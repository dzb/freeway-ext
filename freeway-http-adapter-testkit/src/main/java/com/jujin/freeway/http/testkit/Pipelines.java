package com.jujin.freeway.http.testkit;

import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import java.util.List;

/**
 * The pipeline a contract test starts from: the routes under test plus any WebSocket groups, with
 * CORS and health disabled. It hands the parts to {@link com.jujin.freeway.http.WebServerBuilder}
 * instead of prebuilding a {@code RequestComponents}, so a contract test starts its server the same
 * way a standalone application does — noop event sink sentinel, default error handler appended.
 */
public record Pipelines(List<Route> routes, List<WebSocketGroup> webSocketGroups) {

  public static Pipelines of(List<Route> routes) {
    return new Pipelines(List.copyOf(routes), List.of());
  }

  public static Pipelines of(List<Route> routes, List<WebSocketGroup> webSocketGroups) {
    return new Pipelines(List.copyOf(routes), List.copyOf(webSocketGroups));
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
