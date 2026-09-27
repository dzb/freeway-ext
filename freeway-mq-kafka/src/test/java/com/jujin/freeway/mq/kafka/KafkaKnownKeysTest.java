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

package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.mq.kafka.KafkaModule.ConfigKeys;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The module's declared vocabulary must cover every key it reads: the reflection over {@link
 * ConfigKeys} is non-empty, and the contribution actually reaches the container — a new key added
 * to {@link KafkaConfig} but not to the table would silently leave the unknown-key check blind to
 * the {@code freeway.kafka} namespace.
 */
class KafkaKnownKeysTest {

  /** Every {@code freeway.*} key this module resolves (grep of the read points). */
  private static final Set<String> READ_KEYS =
      Set.of(
          "freeway.kafka.bootstrap-servers",
          "freeway.kafka.group-id",
          "freeway.kafka.client-id",
          "freeway.kafka.topics",
          "freeway.kafka.poison-policy",
          "freeway.kafka.properties",
          "freeway.kafka.dlq-topic",
          "freeway.kafka.max-retries",
          "freeway.kafka.retry-backoff-ms",
          "freeway.kafka.concurrency",
          "freeway.kafka.dedup-capacity",
          "freeway.kafka.suppress-own");

  @Test
  void tableReflectsEveryKeyTheModuleReads() {
    KnownKeys vocabulary = KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX);

    assertFalse(vocabulary.keys().isEmpty(), "the key table must reflect non-empty");
    assertTrue(
        vocabulary.keys().containsAll(READ_KEYS),
        "every read key must be declared, missing: "
            + READ_KEYS.stream().filter(k -> !vocabulary.keys().contains(k)).toList());
  }

  @Test
  void moduleContributesItsVocabularyToTheContainer() {
    try (Container container = Freeway.create(new KafkaModule())) {
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

  @Test
  void lifecycleHookIdStaysOutOfTheVocabulary() {
    // "freeway.kafka.lifecycle" is a runtime-hook id, not a configurable key: admitting it
    // would report a legitimate contribution id as an unknown configured key.
    KnownKeys vocabulary = KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX);

    assertFalse(
        vocabulary.keys().contains(KafkaModule.LIFECYCLE_HOOK),
        "identity strings must not be declared as keys");
  }
}
