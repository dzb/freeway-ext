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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

// Kafka 4.3.1 deprecates both public MockConsumer constructors (and
// OffsetResetStrategy itself) without a non-deprecated public replacement,
// so the test must keep using the deprecated constructor for now.
@SuppressWarnings("deprecation")
class KafkaSubscriberTest {

  record TestEvent(String value) {}

  private static KafkaConfig config(
      String clientId, String topics, String poison, String dlq, int concurrency, boolean suppress) {
    return KafkaConfig.of(
        "localhost:9092", "test-group", clientId, topics, poison, "", dlq, 1, 0, concurrency, suppress);
  }

  /** A plane wired to a mock producer (its outbound half is unused here). */
  private static KafkaEvents plane(KafkaConfig config) {
    return new KafkaEvents(
        config,
        new JsonCodecDefault(),
        new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer()));
  }

  private static KafkaSubscriber poller(KafkaConfig config, KafkaEvents plane, MockConsumer<String, byte[]> consumer) {
    return new KafkaSubscriber(config, plane, consumer, null);
  }

  private static KafkaSubscriber poller(
      KafkaConfig config,
      KafkaEvents plane,
      MockConsumer<String, byte[]> consumer,
      MockProducer<String, byte[]> dlqProducer) {
    return new KafkaSubscriber(config, plane, consumer, dlqProducer);
  }

  private static void rebalance(KafkaSubscriber subscriber,
      MockConsumer<String, byte[]> consumer, TopicPartition topic) {
    consumer.updateBeginningOffsets(Map.of(topic, 0L));
    subscriber.start(); // consumer.subscribe(...) first — dynamic assignment
    consumer.rebalance(Set.of(topic));
  }

  private static ConsumerRecord<String, byte[]> record(
      String topic, long offset, String key, String value, String originHeader, String origin) {
    var record =
        new ConsumerRecord<>(topic, 0, offset, key, value.getBytes(StandardCharsets.UTF_8));
    if (origin != null) {
      record.headers().add(originHeader, origin.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }

  @Test
  void deliversToDeclaredSubscriptionsUntilClosed() throws Exception {
    var config = config("", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", Map.class, received::add);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "key-1", "{\"x\":1}", null, null));

    Object event = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(event, "a matching subscription must receive the payload");
    assertTrue(event instanceof Map, "payloads decode into the declared type");

    subscriber.close();
    assertTrue(consumer.closed(), "consumer should be closed by the poll loop");
  }

  @Test
  void recordsWithoutAnySubscriptionAreSkippedWithoutBeingRead() throws Exception {
    // The subscription table is the inbound gate: no match means the value is
    // never even parsed — garbage is fine here and stays NOT poison.
    var config = config("", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "k", "this is not json", null, null));

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (plane.stats().skippedNoSubscription() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, plane.stats().skippedNoSubscription());
    assertFalse(consumer.closed(), "an unmatched record never triggers the poison policy");

    subscriber.close();
  }

  @Test
  void poisonMessageIsForwardedToDlq() throws Exception {
    var config = config("", "orders", "skip", "orders-dlq", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var dlqProducer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", TestEvent.class, received::add);

    var subscriber = poller(config, plane, consumer, dlqProducer);
    rebalance(subscriber, consumer, topic);

    // Subscribed type cannot decode this value -> poison -> DLQ.
    consumer.addRecord(record("orders", 0L, "key-1", "this is not json", null, null));

    List<ProducerRecord<String, byte[]>> dlq;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      dlq = dlqProducer.history();
      if (!dlq.isEmpty()) {
        break;
      }
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);

    assertEquals(1, dlq.size(), "poison message should reach the DLQ");
    assertEquals("orders-dlq", dlq.getFirst().topic());
    assertEquals(
        "orders",
        new String(
            dlq.getFirst().headers().lastHeader("X-DLQ-Original-Topic").value(),
            StandardCharsets.UTF_8));
    assertNotNull(dlq.getFirst().headers().lastHeader("X-DLQ-Reason"));
    assertTrue(received.isEmpty(), "poison message must not reach any handler");
    assertFalse(consumer.closed(), "the skip policy keeps the subscriber running");

    subscriber.close();
    assertTrue(consumer.closed());
  }

  @Test
  void poisonPolicyFailStopsTheSubscriberAfterTheDlq() throws Exception {
    // README contract: with a DLQ the record is preserved first, and the policy
    // still decides whether processing continues (skip) or stops (fail).
    var config = config("", "orders", "fail", "orders-dlq", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var dlqProducer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    plane.subscribe("orders", TestEvent.class, e -> {});

    var subscriber = poller(config, plane, consumer, dlqProducer);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "key-1", "not json", null, null));

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (dlqProducer.history().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, dlqProducer.history().size(), "the record is preserved in the DLQ first");

    // The fail policy then stops the loop: it closes the consumer in its
    // finally block, so nothing after the poison record is consumed and the
    // offset stays uncommitted for redelivery.
    long stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!consumer.closed() && System.nanoTime() < stopDeadline) {
      Thread.sleep(20);
    }
    assertTrue(consumer.closed(), "the fail policy must stop the subscriber after the DLQ");
  }

  @Test
  void poisonPolicyFailWithoutADlqStopsTheSubscriber() throws Exception {
    var config = config("", "orders", "fail", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    plane.subscribe("orders", TestEvent.class, e -> {});

    var subscriber = poller(config, plane, consumer, (MockProducer<String, byte[]>) null);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "key-1", "not json", null, null));

    long stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!consumer.closed() && System.nanoTime() < stopDeadline) {
      Thread.sleep(20);
    }
    assertTrue(consumer.closed(), "the fail policy stops the subscriber without a DLQ too");
  }

  @Test
  void handlerFailureIsIsolatedAndNeverPoison() throws Exception {
    // A throwing handler is a consumer bug, not a record defect: the record
    // decoded fine, so retrying or DLQ-ing it would fix nothing.
    var config = config("", "orders", "fail", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    plane.subscribe("orders", TestEvent.class, e -> {
      throw new IllegalStateException("handler bug");
    });

    var subscriber = poller(config, plane, consumer, (MockProducer<String, byte[]>) null);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "k", "{\"value\":\"ok\"}", null, null));

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (plane.stats().handlerFailures() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, plane.stats().handlerFailures(), "the failure is counted");
    assertFalse(consumer.closed(),
        "the poison policy must not react to a handler bug — even under the fail policy");

    subscriber.close();
  }

  @Test
  void concurrentConsumptionDeliversAllMessages() throws Exception {
    var config = config("", "orders", "skip", "", 2, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", Map.class, received::add);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    for (int i = 0; i < 10; i++) {
      consumer.addRecord(
          record("orders", i, "key-" + (i % 3), ("{\"i\":" + i + "}"), null, null));
    }

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (received.size() < 10 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(10, received.size(), "all messages should be delivered with concurrency=2");

    subscriber.close();
    assertTrue(consumer.closed());
  }

  @Test
  void legacyRecordsAreRoutedBySubscriptionNotByTheirHeaders() throws Exception {
    // Pre-teardown records carry a class-channel marker and an X-Event-*
    // origin. Neither header routes anything now: the record's topic and the
    // subscription's declared type decide delivery — and the legacy origin
    // header is still honored for own-suppression.
    var config = config("node-9", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", TestEvent.class, received::add);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    var legacy =
        new ConsumerRecord<>(
            "orders", 0, 0L, "key-1", "{\"value\":\"legacy\"}".getBytes(StandardCharsets.UTF_8));
    legacy
        .headers()
        .add("X-Event-Type", TestEvent.class.getName().getBytes(StandardCharsets.UTF_8));
    legacy.headers().add("X-Event-Channel", "CLASS".getBytes(StandardCharsets.UTF_8));
    legacy.headers().add("X-Event-Origin", "node-2".getBytes(StandardCharsets.UTF_8));
    legacy.headers().add("X-Event-Id", "legacy-id-1".getBytes(StandardCharsets.UTF_8));
    consumer.addRecord(legacy);

    // And a legacy record from THIS node's origin: suppressed.
    var own =
        new ConsumerRecord<>(
            "orders", 0, 1L, "key-2", "{\"value\":\"mine\"}".getBytes(StandardCharsets.UTF_8));
    own.headers().add("X-Event-Origin", "node-9".getBytes(StandardCharsets.UTF_8));
    consumer.addRecord(own);

    Object event = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(event, "a legacy-header record must still be delivered by its topic");
    assertTrue(event instanceof TestEvent);
    assertEquals("legacy", ((TestEvent) event).value());
    // A fixed settle: let the broker-side machinery get a chance to deliver
    // the own-origin record if suppression were broken (it must not).
    subscriber.close();
    assertTrue(consumer.closed(), "the poll loop shut down");
    assertTrue(
        received.stream().noneMatch(e -> ((TestEvent) e).value().equals("mine")),
        "the legacy own-origin record must still be suppressed");
  }

  @Test
  void ownOriginMessagesAreSuppressed() throws Exception {
    var config = config("node-1", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", Map.class, received::add);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    // This node's own re-broadcast (origin == config origin) -> suppressed.
    consumer.addRecord(record("orders", 0L, "key-1", "{\"x\":1}", "ce-fworigin", "node-1"));
    // Foreign event -> delivered.
    consumer.addRecord(record("orders", 1L, "key-1", "{\"x\":2}", "ce-fworigin", "node-2"));

    Object event = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(event, "foreign event must be delivered");
    assertTrue(event instanceof Map);
    assertEquals(2, ((Map<?, ?>) event).get("x"), "the own event must be suppressed");
    assertTrue(received.isEmpty(), "only the foreign event may be delivered");

    subscriber.close();
    assertTrue(consumer.closed());
  }

  @Test
  void suppressionCanBeDisabled() throws Exception {
    var config = config("node-1", "orders", "skip", "", 1, false);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", Map.class, received::add);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "key-1", "{\"x\":1}", "ce-fworigin", "node-1"));

    Object event = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(event, "with suppress-own=false own events are delivered");
    assertTrue(received.isEmpty());

    subscriber.close();
    assertTrue(consumer.closed());
  }

  @Test
  void tombstoneRecordsAreAcknowledgedWithoutReading() throws Exception {
    var config = config("", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var received = new LinkedBlockingQueue<Object>();
    plane.subscribe("orders", Map.class, received::add);

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(new ConsumerRecord<>("orders", 0, 0L, "key-1", null));
    consumer.addRecord(record("orders", 1L, "key-2", "{\"x\":1}", null, null));

    // The live record after the tombstone arrives: the tombstone was skipped,
    // not retried into a stall.
    Object event = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(event, "delivery continues past a tombstone");
    assertTrue(received.isEmpty(), "the tombstone itself delivered nothing");

    subscriber.close();
  }

  @Test
  void inboundTraceIsRestoredAroundDelivery() throws Exception {
    // The sender's span must reach handlers: a record carrying ce-traceparent
    // delivers with that trace bound on the poll worker.
    var config = config("node-1", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var seen = new LinkedBlockingQueue<Optional<InvocationContext>>();
    plane.subscribe("orders", Map.class, payload -> seen.add(InvocationContext.current()));

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    var record =
        new ConsumerRecord<>(
            "orders", 0, 0L, "key-1", "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
    record
        .headers()
        .add(
            "ce-traceparent",
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
                .getBytes(StandardCharsets.UTF_8));
    record
        .headers()
        .add("ce-tracestate", "rojo=00f067aa0ba902b7".getBytes(StandardCharsets.UTF_8));
    consumer.addRecord(record);

    var captured = seen.poll(5, TimeUnit.SECONDS);
    assertNotNull(captured, "the event must be delivered");
    var trace = captured.orElseThrow(() -> new AssertionError("no context bound")).trace();
    assertNotNull(trace, "the wire trace must be restored around delivery");
    assertEquals("0af7651916cd43dd8448eb211c80319c", trace.traceId());
    assertEquals("rojo=00f067aa0ba902b7", trace.traceState());

    subscriber.close();
    assertTrue(consumer.closed());
  }

  @Test
  void tracelessRecordDeliversBare() throws Exception {
    // No trace headers: nothing is fabricated and nothing is cleared — the
    // fresh poll worker holds no ambient, so handlers observe empty.
    var config = config("node-1", "orders", "skip", "", 1, true);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);
    var plane = plane(config);
    var seen = new LinkedBlockingQueue<Optional<InvocationContext>>();
    plane.subscribe("orders", Map.class, payload -> seen.add(InvocationContext.current()));

    var subscriber = poller(config, plane, consumer);
    rebalance(subscriber, consumer, topic);

    consumer.addRecord(record("orders", 0L, "key-1", "{\"x\":1}", null, null));

    var captured = seen.poll(5, TimeUnit.SECONDS);
    assertNotNull(captured, "the event must be delivered");
    assertTrue(captured.isEmpty(), "no wire trace means no bound context");

    subscriber.close();
    assertTrue(consumer.closed());
  }
}
