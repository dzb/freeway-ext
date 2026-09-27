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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.http.undertow.UndertowHttpEngineModule.ConfigKeys;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The adapter's declared vocabulary must cover every key it reads: the reflection over {@link
 * ConfigKeys} is non-empty, and the contribution actually reaches the container — a new key added
 * to the engine but not to the table would silently leave the unknown-key check blind to that
 * namespace.
 */
class UndertowKnownKeysTest {

  /** Every {@code freeway.*} key this adapter resolves (grep of the read points). */
  private static final Set<String> READ_KEYS =
      Set.of("freeway.http.undertow.dispatch-io", "freeway.http.websocket.max-frame-size");

  @Test
  void tableReflectsEveryKeyTheEngineReads() {
    KnownKeys vocabulary = KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX);

    assertFalse(vocabulary.keys().isEmpty(), "the key table must reflect non-empty");
    assertTrue(
        vocabulary.keys().containsAll(READ_KEYS),
        "every read key must be declared, missing: "
            + READ_KEYS.stream().filter(k -> !vocabulary.keys().contains(k)).toList());
  }

  @Test
  void moduleContributesItsVocabularyToTheContainer() {
    try (Container container = Freeway.create(new UndertowHttpEngineModule())) {
      Set<String> known =
          container.extension(KnownKeys.class).all().stream()
              .flatMap(vocabulary -> vocabulary.keys().stream())
              .collect(Collectors.toSet());

      assertTrue(
          known.containsAll(READ_KEYS),
          "the module's contribution must reach the container extension, missing: "
              + READ_KEYS.stream().filter(k -> !known.contains(k)).toList());
    }
  }
}
