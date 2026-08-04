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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaDeserializationTest {

  @Test
  void untypedMessageDeserializesAsMap() {
    assertEquals(Map.class, KafkaSubscriber.resolveEventType(null, Set.of()));
    assertEquals(Map.class, KafkaSubscriber.resolveEventType("  ", Set.of()));
  }

  @Test
  void allowedTypedMessageUsesDeclaredType() {
    assertEquals(
        String.class,
        KafkaSubscriber.resolveEventType("java.lang.String", Set.of("java.lang.String")));
  }

  @Test
  void disallowedTypeIsRejected() {
    assertThrows(
        SecurityException.class,
        () -> KafkaSubscriber.resolveEventType("java.lang.Runtime", Set.of("java.lang.String")));
    assertThrows(
        SecurityException.class,
        () -> KafkaSubscriber.resolveEventType("java.lang.Runtime", Set.of()));
  }

  @Test
  void allowedButMissingTypeIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KafkaSubscriber.resolveEventType(
                "com.acme.DoesNotExist", Set.of("com.acme.DoesNotExist")));
  }

  @Test
  void tombstoneRecordIsDetected() {
    var tombstone = new ConsumerRecord<String, byte[]>("orders", 0, 5L, "key-1", null);
    var normal = new ConsumerRecord<String, byte[]>("orders", 0, 5L, "key-1", new byte[] {1});
    assertTrue(KafkaSubscriber.isTombstone(tombstone));
    assertFalse(KafkaSubscriber.isTombstone(normal));
  }
}
