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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaEventsSendTest {

  record PlainTestEvent(String value) {}

  private static KafkaEvents newPlane(String clientId, MockProducer<String, byte[]> producer) {
    var config =
        KafkaConfig.of(
            "localhost:9092", "test-group", clientId, "orders", "skip", "", "", 1, 0, 1, true, 0);
    return new KafkaEvents(config, new JsonCodecDefault(), producer);
  }

  private static String header(ProducerRecord<String, byte[]> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  @Test
  void sendDoesNotThrowWhenTheProducerRejectsTheRecord() {
    // send is fire-and-forget by contract: KafkaProducer.send signals failures
    // both synchronously and through the callback, and neither may surface on
    // the publishing thread — the caller's transaction, if any, is already in.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    producer.sendException = new IllegalStateException("broker unavailable");
    KafkaEvents plane = newPlane("", producer);

    plane.send("orders", new PlainTestEvent("v"));

    assertTrue(producer.history().isEmpty(), "the rejected record must not be reported as sent");
    assertEquals(1, plane.stats().sendFailures(), "the failure is counted, not thrown");
  }

  @Test
  void sendSkipsANullPayload() {
    // A null record value is a Kafka tombstone (compaction marker), not an
    // event — this plane has no signal semantics; it skips loudly.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEvents plane = newPlane("", producer);

    plane.send("orders", null);

    assertTrue(producer.history().isEmpty(), "nothing to send, nothing sent");
  }

  @Test
  void explicitKeyBecomesTheRecordKeyAndSubject() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEvents plane = newPlane("", producer);
    plane.send("orders", new PlainTestEvent("x"), "agg-42");

    ProducerRecord<String, byte[]> record = producer.history().getFirst();
    assertEquals("agg-42", record.key(), "the argument becomes the partition key");
    assertEquals("agg-42", header(record, "ce-subject"), "and rides the envelope as subject");
  }

  @Test
  void plainSendCarriesNoKeyOrSubject() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEvents plane = newPlane("", producer);
    plane.send("orders", new PlainTestEvent("x"));

    ProducerRecord<String, byte[]> record = producer.history().getFirst();
    assertNull(record.key(), "without an argument the record key stays null");
    assertNull(header(record, "ce-subject"), "and no subject rides the envelope");
  }

  @Test
  void envelopeCarriesCloudEventsAttributes() {
    // CloudEvents Kafka binding: same logical envelope as the WS mesh's JSON
    // frames, carried as ce- headers; the record value stays the JSON payload.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEvents plane = newPlane("node-1", producer);
    plane.send("orders", new PlainTestEvent("v"));

    ProducerRecord<String, byte[]> record = producer.history().getFirst();
    assertEquals("1.0", header(record, "ce-specversion"));
    assertEquals(
        "freeway://node-1",
        header(record, "ce-source"),
        "kafka has no service concept: the source names the node");
    assertEquals(
        "node-1", header(record, "ce-fworigin"), "the node identity (clientId) must be stamped");
    assertEquals(
        "topic",
        header(record, "ce-fwchannel"),
        "the class-channel died with the bridge; every send is a topic");
    assertEquals("application/json", header(record, "ce-datacontenttype"), "the value is JSON");
    assertNotNull(header(record, "ce-time"), "send time must be stamped");
    assertNotNull(header(record, "ce-id"), "every envelope must carry an id");
    // Informational only: routing never resolves classes off the wire.
    assertEquals(PlainTestEvent.class.getName(), header(record, "ce-type"));
  }

  @Test
  void eachSendMintsItsOwnId() {
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEvents plane = newPlane("", producer);
    plane.send("orders", new PlainTestEvent("x"));
    plane.send("orders", new PlainTestEvent("y"));

    assertFalse(
        header(producer.history().getFirst(), "ce-id")
            .equals(header(producer.history().getLast(), "ce-id")),
        "cross-transport shared identity died with the bus bridge — one send, one id");
  }

  @Test
  void ambientTraceIsStampedAsCeHeaders() {
    // The same traceparent/tracestate the mesh stamps as JSON extensions,
    // here as ce- headers per the Kafka binding — absent when the sending
    // thread holds no trace.
    var producer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    KafkaEvents plane = newPlane("node-1", producer);
    plane.send("orders", new PlainTestEvent("x"));
    assertNull(
        header(producer.history().getFirst(), "ce-traceparent"), "a traceless send stamps nothing");

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
      plane.send("orders", new PlainTestEvent("y"));
    } finally {
      InvocationContext.replaceAmbient(previous);
    }

    ProducerRecord<String, byte[]> stamped = producer.history().getLast();
    assertEquals(
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        header(stamped, "ce-traceparent"));
    assertEquals("rojo=00f067aa0ba902b7", header(stamped, "ce-tracestate"));
  }
}
