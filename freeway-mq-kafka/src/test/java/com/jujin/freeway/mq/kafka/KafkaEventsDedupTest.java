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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/**
 * Single-transport redelivery suppression: the bounded seen-set in {@link KafkaEvents} drops a
 * second copy of an already-dispatched record (rebalance redelivery carries the same CE id).
 * Opt-in via {@code freeway.kafka.dedup-capacity}; off by default. No cross-transport identity
 * is involved — each plane mints its own ids.
 */
class KafkaEventsDedupTest {

  record PlainTestEvent(String value) {}

  private static KafkaEvents planeWithWindow(int capacity) {
    var config =
        KafkaConfig.of(
            "localhost:9092", "test-group", "", "orders", "skip", "", "", 1, 0, 1, true, capacity);
    return new KafkaEvents(
        config,
        new JsonCodecDefault(),
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer()));
  }

  private static ConsumerRecord<String, byte[]> record(String id, String json) {
    var record =
        new ConsumerRecord<>("orders", 0, 0L, "key-1", json.getBytes(StandardCharsets.UTF_8));
    if (id != null) {
      record.headers().add(KafkaHeaders.CE_ID, id.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }

  @Test
  void duplicateRedeliveryIsSuppressed() {
    KafkaEvents plane = planeWithWindow(16);
    List<Object> received = new ArrayList<>();
    plane.subscribe("orders", PlainTestEvent.class, received::add);

    plane.handle(record("evt-1", "{\"value\":\"x\"}"));
    plane.handle(record("evt-1", "{\"value\":\"x\"}"));

    assertEquals(1, received.size(), "the second copy must be dropped before dispatch");
    assertEquals(1, plane.stats().delivered());
    assertEquals(1, plane.stats().duplicatesDropped(), "the drop is counted, not silent");
  }

  @Test
  void poisonStillThrowsWithWindowArmed() {
    // Claim happens after successful decode: a poison record is never claimed,
    // so its redelivery still reaches the DLQ path instead of being swallowed.
    KafkaEvents plane = planeWithWindow(16);
    List<Object> received = new ArrayList<>();
    plane.subscribe("orders", PlainTestEvent.class, received::add);

    assertThrows(
        RuntimeException.class, () -> plane.handle(record("bad-1", "{invalid")),
        "an undecodable record must stay loud with the window armed");
    assertThrows(
        RuntimeException.class,
        () -> plane.handle(record("bad-1", "{invalid")),
        "its redelivery must also stay loud — never claimed, never dropped");
    assertTrue(received.isEmpty());
    assertEquals(0, plane.stats().duplicatesDropped());
  }

  @Test
  void windowIsBounded() {
    KafkaEvents plane = planeWithWindow(2);
    List<String> received = new ArrayList<>();
    plane.subscribe(
        "orders", PlainTestEvent.class, e -> received.add(((PlainTestEvent) e).value()));

    plane.handle(record("id-a", "{\"value\":\"a\"}"));
    plane.handle(record("id-b", "{\"value\":\"b\"}"));
    plane.handle(record("id-c", "{\"value\":\"c\"}")); // evicts id-a
    plane.handle(record("id-a", "{\"value\":\"a\"}")); // evicted, so delivered

    assertEquals(
        List.of("a", "b", "c", "a"),
        received,
        "a capacity-2 window remembers only the last two ids");
  }

  @Test
  void dedupOffByDefault() {
    KafkaEvents plane = planeWithWindow(0);
    List<Object> received = new ArrayList<>();
    plane.subscribe("orders", PlainTestEvent.class, received::add);

    plane.handle(record("evt-1", "{\"value\":\"x\"}"));
    plane.handle(record("evt-1", "{\"value\":\"x\"}"));

    assertEquals(2, received.size(), "with no window armed, both copies dispatch");
    assertEquals(0, plane.stats().duplicatesDropped());
  }

  @Test
  void blankOrNullIdAlwaysDelivers() {
    // No identity to correlate on (legacy producers may omit it): deliver
    // rather than drop, however the window is configured.
    KafkaEvents plane = planeWithWindow(16);
    List<Object> received = new ArrayList<>();
    plane.subscribe("orders", PlainTestEvent.class, received::add);

    plane.handle(record(null, "{\"value\":\"x\"}"));
    plane.handle(record(null, "{\"value\":\"x\"}"));

    assertEquals(2, received.size(), "an event with no identity must never be deduped away");
  }
}
