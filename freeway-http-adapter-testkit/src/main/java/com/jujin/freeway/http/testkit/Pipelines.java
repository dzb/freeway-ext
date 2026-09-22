package com.jujin.freeway.http.testkit;

import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ErrorHandler;
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
public record Pipelines(
    List<Route> routes, List<WebSocketGroup> webSocketGroups, List<ErrorHandler> errorHandlers) {

  public Pipelines {
    routes = List.copyOf(routes);
    webSocketGroups = List.copyOf(webSocketGroups);
    errorHandlers = List.copyOf(errorHandlers);
  }

  public static Pipelines of(List<Route> routes) {
    return new Pipelines(routes, List.of(), List.of());
  }

  public static Pipelines of(List<Route> routes, List<WebSocketGroup> webSocketGroups) {
    return new Pipelines(routes, webSocketGroups, List.of());
  }

  /**
   * The pipeline plus error handlers that must run <em>before</em> the default one — the shape a
   * test needs when it asserts on a thrown exception or on a mapping the framework installs (413
   * for an oversized body). Handlers are consulted in order and the first one returning true wins.
   */
  public static Pipelines of(
      List<Route> routes, List<WebSocketGroup> webSocketGroups, List<ErrorHandler> errorHandlers) {
    return new Pipelines(routes, webSocketGroups, errorHandlers);
  }

  /**
   * CORS disabled: the contract tests send no {@code Origin}, so an active filter is pure noise.
   */
  public static CorsFilter disabledCors() {
    return CorsFilter.defaults().withEnabled(false);
  }

  /** Health disabled: no contract probes it, and every engine must run the same pipeline. */
  public static HealthFilter disabledHealth() {
    return HealthFilter.defaults().withEnabled(false);
  }
}
