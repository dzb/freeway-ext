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

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;
import java.util.Map;

/** Map-backed {@link SymbolSource} doubles that behave like the container's chain. */
public final class Symbols {

  private Symbols() {}

  /**
   * A source holding exactly the given values. It resolves {@link SymbolSpec}s through a {@link
   * CoercerDefault}, as the container does — a double without that step passes under the container
   * and fails on the standalone path, which is exactly the difference a config test is meant to
   * cover.
   */
  public static SymbolSource of(Map<String, String> values) {
    Coercer coercer = new CoercerDefault();
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

      @Override
      public <T> T resolve(SymbolSpec<T> spec) {
        return spec.parse(resolve(spec.key(), null), coercer);
      }
    };
  }
}
