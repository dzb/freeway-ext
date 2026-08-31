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
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.EventBus;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaEventSinkTest {

  /** Event carrying a partitioning key, mirroring a domain record with an aggregate id. */
  record KeyedTestEvent(String id) implements EventBus.Keyed {
    @Override
    public String key() {
      return id;
    }
  }

  record PlainTestEvent(String value) {}

  private static KafkaEventSink newSink(String clientId, MockProducer<String, byte[]> producer) {
    var config =
        KafkaConfig.of(
            "localhost:9092", "test-group", clientId, "orders", "", "skip", "", "", 1, 0, 1, true);
    return new KafkaEventSink(config, new JsonCodecDefault(), producer);
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
    KafkaEventSink sink = newSink("", producer);
    sink.send("orders", new KeyedTestEvent("agg-42"), EventSink.Channel.CLASS);

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
    KafkaEventSink sink = newSink("", producer);
    sink.send("orders", new PlainTestEvent("x"), EventSink.Channel.CLASS);

    assertNull(
        producer.history().getFirst().key(), "events without a key must keep a null record key");
  }

  @Test
  void envelopeCarriesTypeOriginChannelAndId() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("node-1", producer);
    sink.send("orders", new KeyedTestEvent("agg-1"), EventSink.Channel.TOPIC);

    ProducerRecord<String, byte[]> record = producer.history().getFirst();
    assertEquals(
        KeyedTestEvent.class.getName(),
        header(record, "X-Event-Type"),
        "the concrete event class must be carried for typed deserialization");
    assertEquals(
        "node-1", header(record, "X-Event-Origin"), "the node identity (clientId) must be stamped");
    assertEquals(
        "TOPIC", header(record, "X-Event-Channel"), "the dispatch channel must be stamped");
    assertNotNull(header(record, "X-Event-Id"), "every envelope must carry an event id");
    assertFalse(header(record, "X-Event-Id").isBlank());
  }

  @Test
  void twoArgSendDefaultsToClassChannel() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("", producer);
    sink.send("orders", new PlainTestEvent("x"));

    assertEquals(
        "CLASS",
        header(producer.history().getFirst(), "X-Event-Channel"),
        "a direct two-arg send passes a concrete event, so it dispatches on the class channel "
            + "(matching CloudEventSink)");
  }

  @Test
  void sendCarriesTheBusMintedEventId() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("", producer);
    sink.send("orders", new PlainTestEvent("x"), EventSink.Channel.CLASS, "bus-id-7");
    sink.send("orders", new PlainTestEvent("y"), EventSink.Channel.CLASS, "bus-id-7");

    assertEquals(
        "bus-id-7",
        header(producer.history().getFirst(), "X-Event-Id"),
        "the id handed in by the bus must be reused verbatim, not replaced by a fresh UUID");
    assertEquals(
        header(producer.history().getFirst(), "X-Event-Id"),
        header(producer.history().getLast(), "X-Event-Id"),
        "two transports carrying one dispatch must expose one identity");
  }
}
