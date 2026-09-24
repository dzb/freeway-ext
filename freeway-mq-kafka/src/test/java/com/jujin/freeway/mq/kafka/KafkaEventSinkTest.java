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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.EventSink;
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
  void sendDoesNotThrowWhenTheProducerRejectsTheRecord() {
    // EventSink.send must not throw: KafkaProducer.send signals failures both
    // synchronously and through the callback, and the publishing thread must
    // survive either. The local dispatch already happened.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    producer.sendException = new IllegalStateException("broker unavailable");
    KafkaEventSink sink = newSink("", producer);

    sink.send("orders", new PlainTestEvent("v"), EventSink.Channel.CLASS, "test-id-1");

    assertTrue(producer.history().isEmpty(), "the rejected record must not be reported as sent");
  }

  @Test
  void sendIgnoresANullPayload() {
    // The bus allows a null topic payload; the wire format carries the class
    // name, so the transport skips it instead of failing on it.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("", producer);

    sink.send("orders", null, EventSink.Channel.TOPIC, "test-id-2");

    assertTrue(producer.history().isEmpty(), "nothing to send, nothing sent");
  }

  @Test
  void keyedEventCarriesPartitionKey() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("", producer);
    sink.send("orders", new KeyedTestEvent("agg-42"), EventSink.Channel.CLASS, "test-id-3");

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
    sink.send("orders", new PlainTestEvent("x"), EventSink.Channel.CLASS, "test-id-4");

    assertNull(
        producer.history().getFirst().key(), "events without a key must keep a null record key");
  }

  @Test
  void envelopeCarriesCloudEventsAttributes() {
    // CloudEvents Kafka binding: same logical envelope as the WS mesh's JSON
    // frames, carried as ce- headers; the record value stays the JSON event.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("node-1", producer);
    sink.send("orders", new KeyedTestEvent("agg-1"), EventSink.Channel.TOPIC, "test-id-5");

    ProducerRecord<String, byte[]> record = producer.history().getFirst();
    assertEquals(
        KeyedTestEvent.class.getName(),
        header(record, "ce-type"),
        "the concrete event class must be carried for typed deserialization");
    assertEquals("1.0", header(record, "ce-specversion"));
    assertEquals(
        "freeway://node-1",
        header(record, "ce-source"),
        "kafka has no service concept: the source names the node");
    assertEquals(
        "node-1", header(record, "ce-fworigin"), "the node identity (clientId) must be stamped");
    assertEquals(
        "TOPIC", header(record, "ce-fwchannel"), "the dispatch channel must be stamped");
    assertEquals("orders", header(record, "ce-fwtopic"), "the local sink topic must travel");
    assertEquals(
        "application/json", header(record, "ce-datacontenttype"), "the value is JSON");
    assertNotNull(header(record, "ce-time"), "send time must be stamped");
    assertNotNull(header(record, "ce-id"), "every envelope must carry an event id");
    assertFalse(header(record, "ce-id").isBlank());
  }

  @Test
  void subjectIsStampedOnTheClassChannelOnly() {
    // Mesh parity: the Keyed ordering key rides ce-subject for typed events;
    // a topic payload is opaque and carries no subject — like on the mesh.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("node-1", producer);
    sink.send("orders", new KeyedTestEvent("agg-1"), EventSink.Channel.CLASS, "test-id-6");
    sink.send("orders", new KeyedTestEvent("agg-2"), EventSink.Channel.TOPIC, "test-id-7");

    assertEquals(
        "agg-1",
        header(producer.history().getFirst(), "ce-subject"),
        "the partitioning key must ride the envelope on the class channel");
    assertNull(
        header(producer.history().getLast(), "ce-subject"),
        "a topic payload is opaque — no subject, like on the mesh");
  }

  @Test
  void ambientTraceIsStampedAsCeHeaders() {
    // The same traceparent/tracestate the mesh stamps as JSON extensions,
    // here as ce- headers per the Kafka binding — absent when the sending
    // thread holds no trace.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEventSink sink = newSink("node-1", producer);
    sink.send("orders", new PlainTestEvent("x"), EventSink.Channel.CLASS, "test-id-8");
    assertNull(
        header(producer.history().getFirst(), "ce-traceparent"),
        "a traceless send stamps nothing");

    var trace =
        new TraceContext(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            null,
            "01",
            "rojo=00f067aa0ba902b7");
    InvocationContext previous =
        InvocationContext.replaceAmbient(InvocationContext.of(trace, null, null));
    try {
      sink.send("orders", new PlainTestEvent("y"), EventSink.Channel.CLASS, "test-id-9");
    } finally {
      InvocationContext.replaceAmbient(previous);
    }

    ProducerRecord<String, byte[]> stamped = producer.history().getLast();
    assertEquals(
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        header(stamped, "ce-traceparent"));
    assertEquals(
        "rojo=00f067aa0ba902b7", header(stamped, "ce-tracestate"));
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
        header(producer.history().getFirst(), "ce-id"),
        "the id handed in by the bus must be reused verbatim, not replaced by a fresh UUID");
    assertEquals(
        header(producer.history().getFirst(), "ce-id"),
        header(producer.history().getLast(), "ce-id"),
        "two transports carrying one dispatch must expose one identity");
  }
}
