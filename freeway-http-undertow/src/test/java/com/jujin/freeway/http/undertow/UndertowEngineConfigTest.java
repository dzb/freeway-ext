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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cascade wiring for the Undertow adapter knobs: composed use must resolve through the injected
 * {@link SymbolSource}, not JVM properties alone.
 */
class UndertowEngineConfigTest {

  private static SymbolSource symbols(Map<String, String> values) {
    return new SymbolSource() {
      @Override
      public String resolve(String name) {
        if (!values.containsKey(name)) {
          throw new UnknownSymbolException(name);
        }
        return values.get(name);
      }

      @Override
      public String resolve(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
      }

      @Override
      public String expand(String input) {
        return input;
      }
    };
  }

  @Test
  void malformedMaxFrameSizeFailsFastNamingTheKey() {
    // "banana" exists nowhere in system properties: only the injected
    // cascade source can deliver it to startup.
    var engine =
        new UndertowWebEngine(
            new JsonCodecDefault(),
            new CoercerDefault(),
            symbols(Map.of("freeway.http.websocket.max-frame-size", "banana")));
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> engine.start(config, ctx -> {}));
    assertTrue(
        e.getMessage().contains("freeway.http.websocket.max-frame-size"),
        "failure must name the key, got: " + e.getMessage());
  }

  @Test
  void injectedMaxFrameSizeReachesStartup() throws Exception {
    var engine =
        new UndertowWebEngine(
            new JsonCodecDefault(),
            new CoercerDefault(),
            symbols(Map.of("freeway.http.websocket.max-frame-size", "12345")));
    var config = new HttpServerConfig("127.0.0.1", 0, 64, Duration.ofSeconds(5));
    try (var handle = engine.start(config, ctx -> {})) {
      Field field = UndertowWebEngine.class.getDeclaredField("wsMaxMessageSize");
      field.setAccessible(true);
      assertEquals(12345L, field.getLong(engine));
    }
  }

  @Test
  void containerSelectsCascadeWiredConstructor() {
    // The container picks the max-param constructor, whose SymbolSource
    // parameter resolves from the container builtin. Successful creation is
    // the assertion: any unresolvable parameter fails here with
    // MissingBindingException instead of silently falling back.
    // create() injects without the singleton proxy, exposing the engine.
    try (Container container = Freeway.create(new HttpModule(), new UndertowWebEngineModule())) {
      UndertowWebEngine engine = container.create(UndertowWebEngine.class);
      assertNotNull(readField(engine, "symbols"));
    }
  }

  private static Object readField(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
