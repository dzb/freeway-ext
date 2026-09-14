package com.jujin.freeway.http.testkit;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;

/**
 * Starts a server for tests that assemble their own engine (a custom TLS setup, a transport limit
 * probe) instead of going through {@link EngineFixture}.
 *
 * <p>It is deliberately the same assembly path a standalone application takes — CORS and health
 * disabled, the default error handler appended, noop event sink — rather than a hand-built {@code
 * RequestComponents}: an adapter test that wires the server by hand is not testing the server
 * applications actually get. The engine is passed in because these tests configure it themselves.
 */
public final class TestServers {

  /** Starts a server on the given engine with the pipeline under test. */
  public static WebServer start(HttpEngine engine, HttpServerConfig config, Pipelines pipeline) {
    var builder =
        WebServerBuilder.builder()
            .engine(engine)
            .config(config)
            .cors(Pipelines.disabledCors())
            .health(Pipelines.disabledHealth());
    for (var route : pipeline.routes()) {
      builder.route(route);
    }
    for (var group : pipeline.webSocketGroups()) {
      builder.webSocketGroup(group);
    }
    for (var handler : pipeline.errorHandlers()) {
      builder.errorHandler(handler);
    }
    return builder.build();
  }

  private TestServers() {}
}
