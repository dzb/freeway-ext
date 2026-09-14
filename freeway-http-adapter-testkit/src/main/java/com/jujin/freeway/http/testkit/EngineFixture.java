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

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Base class for the shared engine contracts: it owns everything that is not engine-specific (the
 * server configuration, the pipeline wiring, resource lookup), so an adapter's contract test only
 * supplies its engine.
 */
public abstract class EngineFixture {

  /** The engine under test, freshly constructed through its standalone constructor. */
  protected abstract HttpEngine newEngine();

  /** The adapter's name, used in failure messages. */
  protected abstract String engineName();

  /** Starts a server with the test configuration and the given pipeline. */
  protected final WebServer start(Pipelines pipeline) {
    return start(pipeline, defaultConfig());
  }

  /**
   * Starts a server with an explicit configuration (used by the compression contract). Assembly
   * goes through {@link WebServerBuilder} — the standalone path an application takes — so the
   * contracts run against the same server shape as production: the noop event sink sentinel (no
   * per-request event objects for a server nobody observes), the default error handler appended,
   * and CORS/health explicitly disabled.
   */
  protected final WebServer start(Pipelines pipeline, HttpServerConfig config) {
    var builder =
        WebServerBuilder.builder()
            .engine(newEngine())
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
    var server = builder.build();
    server.start();
    return server;
  }

  /** The shared test configuration: loopback, ephemeral port, short timeouts. */
  protected final HttpServerConfig defaultConfig() {
    return HttpServerConfig.defaults()
        .withPort(0)
        .withBacklog(64)
        .withShutdownGrace(Duration.ofSeconds(5));
  }

  /** A route handler that echoes the request body, for engine-agnostic pipelines. */
  protected final JsonCodecDefault jsonCodec() {
    return new JsonCodecDefault();
  }

  protected final CoercerDefault coercer() {
    return new CoercerDefault();
  }

  /** A URI on the running server. */
  protected static URI uri(WebServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  /** A test resource from the calling module's own test classpath (keystore fixtures). */
  protected final Path resource(String name) {
    URL url = getClass().getResource(name);
    if (url == null) {
      throw new IllegalStateException("Missing test resource " + name + " for " + engineName());
    }
    try {
      return Path.of(url.toURI());
    } catch (Exception ex) {
      throw new IllegalStateException("Unreadable test resource " + name, ex);
    }
  }
}
