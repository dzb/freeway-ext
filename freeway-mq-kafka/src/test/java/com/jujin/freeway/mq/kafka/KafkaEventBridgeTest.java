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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.EventBridge;
import com.jujin.freeway.ioc.EventBus;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaEventBridgeTest {

  /** Event carrying a partitioning key, mirroring a domain record with an aggregate id. */
  record KeyedTestEvent(String id) implements EventBus.Keyed {
    @Override
    public String key() {
      return id;
    }
  }

  record PlainTestEvent(String value) {}

  private static KafkaEventBridge bridge(String clientId, MockProducer<String, byte[]> producer) {
    var config =
        new KafkaConfig(
            "localhost:9092", "test-group", clientId, "orders", "", "skip", "", "", 1, 0, 1, true);
    return new KafkaEventBridge(config, new JsonCodecDefault(), producer);
  }

  private static String header(ProducerRecord<String, byte[]> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  @Test
  void keyedEventCarriesPartitionKey() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventBridge bridge = bridge("", producer);
    bridge.send("orders", new KeyedTestEvent("agg-42"), EventBridge.Channel.CLASS);

    assertEquals(
        "agg-42",
        producer.history().getFirst().key(),
        "an EventBus.Keyed event's key must become the Kafka record key");
  }

  @Test
  void plainEventCarriesNullKey() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventBridge bridge = bridge("", producer);
    bridge.send("orders", new PlainTestEvent("x"), EventBridge.Channel.CLASS);

    assertNull(producer.history().getFirst().key(),
        "events without a key must keep a null record key");
  }

  @Test
  void envelopeCarriesTypeOriginChannelAndId() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventBridge bridge = bridge("node-1", producer);
    bridge.send("orders", new KeyedTestEvent("agg-1"), EventBridge.Channel.TOPIC);

    ProducerRecord<String, byte[]> record = producer.history().getFirst();
    assertEquals(
        KeyedTestEvent.class.getName(),
        header(record, "X-Event-Type"),
        "the concrete event class must be carried for typed deserialization");
    assertEquals("node-1", header(record, "X-Event-Origin"),
        "the node identity (clientId) must be stamped");
    assertEquals("TOPIC", header(record, "X-Event-Channel"),
        "the dispatch channel must be stamped");
    assertNotNull(header(record, "X-Event-Id"), "every envelope must carry an event id");
    assertFalse(header(record, "X-Event-Id").isBlank());
  }
}
