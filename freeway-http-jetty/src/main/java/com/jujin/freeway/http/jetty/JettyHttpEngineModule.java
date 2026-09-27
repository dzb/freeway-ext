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

package com.jujin.freeway.http.jetty;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;

/** IoC module that installs the Jetty HTTP engine as the primary engine. */
public final class JettyHttpEngineModule implements ModuleEx {
  @Override
  public void bind(Binder binder) {
    binder.bind(HttpEngine.class).to(JettyHttpEngine.class).id("jetty").primary();

    // Declared vocabulary for the unknown-key check.
    binder.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
  }

  /**
   * This adapter's own config keys, spelled as full literals. It reads the shared {@code
   * freeway.http.ssl.*} / {@code freeway.http.server.*} section through {@code
   * HttpModule.ConfigKeys} (core's vocabulary declares those); the keys below are the ones only
   * this engine resolves.
   */
  public static final class ConfigKeys {
    private ConfigKeys() {}

    /** The namespace this table is fenced to — shared with the core {@code HttpModule}. */
    public static final String PREFIX = "freeway.http";

    /**
     * Dispatch the root handler from I/O threads to the thread pool (default {@code true}); {@code
     * false} keeps it BLOCKING — the counterpart of {@code freeway.http.undertow.dispatch-io}.
     */
    public static final String DISPATCH_IO = "freeway.http.jetty.dispatch-io";

    /**
     * Cleartext HTTP/2 (h2c) toggle (default {@code false}); ignored when TLS is enabled, where
     * {@code freeway.http.ssl.http2} governs h2 via ALPN instead.
     */
    public static final String HTTP2 = "freeway.http.http2";

    /** Password of the private key entry, when it differs from the keystore password. */
    public static final String SSL_KEY_PASSWORD = "freeway.http.ssl.key-password";

    /** Alias of the private key entry to serve, when the keystore holds more than one. */
    public static final String SSL_KEY_ALIAS = "freeway.http.ssl.key-alias";

    /**
     * Maximum WebSocket text/binary message size in bytes (default 65536); 0 or negative disables
     * the limit. The same knob the Undertow adapter reads.
     */
    public static final String WEBSOCKET_MAX_FRAME_SIZE = "freeway.http.websocket.max-frame-size";
  }
}
