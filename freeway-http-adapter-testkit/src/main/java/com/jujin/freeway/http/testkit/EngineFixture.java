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
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.filter.CorsFilter;
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
  protected final TestServers.TestServer start(Pipelines pipeline) {
    return start(pipeline, defaultConfig());
  }

  /**
   * Starts a server with an explicit configuration (used when a contract varies configuration —
   * compression, body limits). Assembly goes through {@link HttpModule} — the same composition an
   * application places — so the contracts run against the same server shape as production: the
   * event sink of a real container, the built-in error mapper consulted last, and CORS/health
   * explicitly disabled.
   */
  protected final TestServers.TestServer start(Pipelines pipeline, HttpServerConfig config) {
    return TestServers.start(newEngine(), config, pipeline);
  }

  /**
   * Starts a server with an explicit CORS policy (used by the WebSocket upgrade contract, which
   * must prove the origin check instead of disabling it).
   */
  protected final TestServers.TestServer start(
      Pipelines pipeline, HttpServerConfig config, CorsFilter cors) {
    return TestServers.start(newEngine(), config, pipeline, cors);
  }

  /** The shared test configuration: loopback, ephemeral port, short timeouts. */
  protected final HttpServerConfig defaultConfig() {
    return HttpServerConfig.defaults()
        .withPort(0)
        .withBacklog(64)
        .withShutdownGrace(Duration.ofSeconds(5));
  }

  /** A JSON codec for an engine's standalone constructor. */
  protected final JsonCodecDefault jsonCodec() {
    return new JsonCodecDefault();
  }

  /** A coercer for the same constructor. */
  protected final CoercerDefault coercer() {
    return new CoercerDefault();
  }

  /** A URI on the running server. */
  protected static URI uri(TestServers.TestServer server, String path) {
    return server.uri(path);
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
