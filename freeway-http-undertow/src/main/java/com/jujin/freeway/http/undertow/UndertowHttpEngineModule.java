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

package com.jujin.freeway.http.undertow;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.symbol.KnownKeys;

/** IoC module that installs the Undertow HTTP engine as the primary engine. */
public final class UndertowHttpEngineModule implements ModuleEx {
  @Override
  public void bind(Binder binder) {
    binder.bind(HttpEngine.class).to(UndertowHttpEngine.class).id("undertow").primary();

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
     * Dispatch the root handler from I/O threads to the worker pool (default {@code true}); {@code
     * false} runs handlers directly on I/O threads — faster for non-blocking handlers, dangerous if
     * they block.
     */
    public static final String DISPATCH_IO = "freeway.http.undertow.dispatch-io";

    /**
     * Maximum WebSocket text/binary message size in bytes (default 65536); 0 or negative disables
     * the limit. The same knob the Jetty adapter reads.
     */
    public static final String WEBSOCKET_MAX_FRAME_SIZE = "freeway.http.websocket.max-frame-size";
  }
}
