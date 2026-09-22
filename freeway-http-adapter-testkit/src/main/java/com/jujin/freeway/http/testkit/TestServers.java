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

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ErrorHandler;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.net.URI;

/**
 * Starts a server for tests that assemble their own engine (a custom TLS setup, a transport limit
 * probe) instead of going through {@link EngineFixture}.
 *
 * <p>It composes the way an application does — place {@link HttpModule}, contribute the parts,
 * override the two bindings the contract varies (the engine and the engine contract) — rather than
 * wiring a server by hand: an adapter test that assembles privately is not testing the server
 * applications actually get. CORS and health are disabled by default because no contract probes
 * them; a contract that must prove the origin check (WebSocket upgrades) supplies its own policy
 * through the overload below instead.
 */
public final class TestServers {

  /**
   * A server under contract test, with the container that assembled it. {@link #close()} closes the
   * container, which disposes the server it realized.
   */
  public record TestServer(Container container, HttpServer server) implements AutoCloseable {

    /** Starts the server and returns it, so a call site can open a try-with-resources on it. */
    public TestServer start() {
      server.start();
      return this;
    }

    /** Closes the container behind this server. */
    public void stop() {
      close();
    }

    public int port() {
      return server.port();
    }

    public String host() {
      return server.host();
    }

    public boolean secure() {
      return server.secure();
    }

    public boolean isRunning() {
      return server.isRunning();
    }

    /** A URI on this server. */
    public URI uri(String path) {
      return URI.create("http://" + host() + ":" + port() + path);
    }

    @Override
    public void close() {
      container.close();
    }
  }

  /** Assembles (without starting) a server on {@code engine} with {@code pipeline}. */
  public static TestServer server(HttpEngine engine, HttpServerConfig config, Pipelines pipeline) {
    return server(engine, config, pipeline, Pipelines.disabledCors());
  }

  /**
   * Assembles with an explicit CORS policy: the shape a contract needs when it must prove the
   * origin check (WebSocket upgrades) instead of disabling CORS as noise.
   */
  public static TestServer server(
      HttpEngine engine, HttpServerConfig config, Pipelines pipeline, CorsFilter cors) {
    Container container =
        Freeway.create(
            new HttpModule(),
            binder -> binder.bind(HttpEngine.class).to(c -> engine).id("adapter").primary(),
            binder -> binder.bind(HttpServerConfig.class).to(c -> config).id("adapter").primary(),
            binder -> {
              binder.bind(CorsFilter.class).to(c -> cors).id("adapter").primary();
              binder
                  .bind(HealthFilter.class)
                  .to(c -> Pipelines.disabledHealth())
                  .id("adapter")
                  .primary();
              for (Route route : pipeline.routes()) {
                binder.contribute(Route.class).add(route);
              }
              for (WebSocketGroup group : pipeline.webSocketGroups()) {
                binder.contribute(WebSocketGroup.class).add(group);
              }
              for (ErrorHandler handler : pipeline.errorHandlers()) {
                binder.contribute(ErrorHandler.class).add(handler);
              }
            });
    return new TestServer(container, container.get(HttpServer.class));
  }

  /** Starts a server on the given engine with the pipeline under test. */
  public static TestServer start(HttpEngine engine, HttpServerConfig config, Pipelines pipeline) {
    return server(engine, config, pipeline).start();
  }

  /** Starts a server with an explicit CORS policy. */
  public static TestServer start(
      HttpEngine engine, HttpServerConfig config, Pipelines pipeline, CorsFilter cors) {
    return server(engine, config, pipeline, cors).start();
  }

  private TestServers() {}
}
