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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaSubscriberTest {

  @Test
  void consumesAndPublishesMessagesUntilClosed() throws Exception {
    var config =
        new KafkaConfig("localhost:9092", "test-group", "", "orders", "", "skip", "", "", 1, 0, 1);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);

    try (Container container = Freeway.create()) {
      EventBus bus = container.get(EventBus.class);
      var received = new LinkedBlockingQueue<Object>();
      bus.subscribe("orders", received::add);

      var subscriber = new KafkaSubscriber(config, bus, new JsonCodecDefault(), consumer, null);
      consumer.updateBeginningOffsets(Map.of(topic, 0L));
      subscriber.start();
      consumer.rebalance(Set.of(topic));

      consumer.addRecord(
          new ConsumerRecord<>(
              "orders", 0, 0L, "key-1", "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));

      Object event = received.poll(5, TimeUnit.SECONDS);
      assertNotNull(event, "message should be published to the EventBus");
      assertTrue(event instanceof Map, "untyped messages deserialize as Map");

      subscriber.close();
      assertTrue(consumer.closed(), "consumer should be closed by the poll loop");
    }
  }

  @Test
  void poisonMessageIsForwardedToDlq() throws Exception {
    var config =
        new KafkaConfig(
            "localhost:9092", "test-group", "", "orders", "", "skip", "", "orders-dlq", 1, 0, 1);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var dlqProducer =
        new MockProducer<String, byte[]>(
            true, null, new StringSerializer(), new ByteArraySerializer());
    var topic = new TopicPartition("orders", 0);

    try (Container container = Freeway.create()) {
      EventBus bus = container.get(EventBus.class);
      var received = new LinkedBlockingQueue<Object>();
      bus.subscribe("orders", received::add);

      var subscriber =
          new KafkaSubscriber(config, bus, new JsonCodecDefault(), consumer, dlqProducer);
      consumer.updateBeginningOffsets(Map.of(topic, 0L));
      subscriber.start();
      consumer.rebalance(Set.of(topic));

      // Typed message whose type is not allowlisted -> poison -> DLQ.
      var record =
          new ConsumerRecord<>("orders", 0, 0L, "key-1", "{}".getBytes(StandardCharsets.UTF_8));
      record.headers().add("X-Event-Type", "com.acme.NotAllowed".getBytes(StandardCharsets.UTF_8));
      consumer.addRecord(record);

      List<org.apache.kafka.clients.producer.ProducerRecord<String, byte[]>> dlq;
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
      assertTrue(received.isEmpty(), "poison message must not reach the EventBus");

      subscriber.close();
      assertTrue(consumer.closed());
    }
  }

  @Test
  void concurrentConsumptionDeliversAllMessages() throws Exception {
    var config =
        new KafkaConfig("localhost:9092", "test-group", "", "orders", "", "skip", "", "", 0, 0, 2);
    var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
    var topic = new TopicPartition("orders", 0);

    try (Container container = Freeway.create()) {
      EventBus bus = container.get(EventBus.class);
      var received = new LinkedBlockingQueue<Object>();
      bus.subscribe("orders", received::add);

      var subscriber = new KafkaSubscriber(config, bus, new JsonCodecDefault(), consumer, null);
      consumer.updateBeginningOffsets(Map.of(topic, 0L));
      subscriber.start();
      consumer.rebalance(Set.of(topic));

      for (int i = 0; i < 10; i++) {
        consumer.addRecord(
            new ConsumerRecord<>(
                "orders",
                0,
                i,
                "key-" + (i % 3),
                ("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8)));
      }

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (received.size() < 10 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(10, received.size(), "all messages should be delivered with concurrency=2");

      subscriber.close();
      assertTrue(consumer.closed());
    }
  }
}
