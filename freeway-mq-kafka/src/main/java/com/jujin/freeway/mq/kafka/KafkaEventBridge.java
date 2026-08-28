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

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.EventBridge;
import com.jujin.freeway.ioc.EventBus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EventBridge} that publishes Freeway events to Kafka topics. Events are serialized as JSON
 * with an {@code X-Event-Type} header carrying the concrete class name, plus {@code X-Event-Origin}
 * (this node's identity), {@code X-Event-Channel} (class/topic dispatch channel) and {@code
 * X-Event-Id} (per-send UUID for correlation).
 *
 * <p>Events implementing {@link EventBus.Keyed} are published with {@code key()} as the record key,
 * so the broker keeps per-aggregate order and consuming subscribers can parallelize across keys.
 * Other events carry a null key.
 *
 * <p><b>Delivery semantics:</b> at-least-once. Producer retries and consumer rebalances can deliver
 * duplicates; consumers that need exactly-once must deduplicate by their own business key. Inbound
 * consumers must not re-bridge received events (see {@code EventBus.publishInbound}).
 */
public class KafkaEventBridge implements EventBridge, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaEventBridge.class);

  private final Producer<String, byte[]> producer;
  private final JsonCodec codec;
  private final String origin;

  public KafkaEventBridge(KafkaConfig config) {
    this(config, new JsonCodecDefault());
  }

  public KafkaEventBridge(KafkaConfig config, JsonCodec codec) {
    this(config, codec, createProducer(config));
  }

  /** Test seam: allows injecting a mock producer. */
  KafkaEventBridge(KafkaConfig config, JsonCodec codec, Producer<String, byte[]> producer) {
    this.producer = producer;
    this.codec = codec;
    this.origin = config.origin();
  }

  private static Producer<String, byte[]> createProducer(KafkaConfig config) {
    var props = new Properties();
    props.put("bootstrap.servers", config.bootstrapServers());
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", ByteArraySerializer.class.getName());
    if (config.clientId() != null && !config.clientId().isBlank()) {
      // Distinct from the consumer's id so producer/consumer are separable in
      // broker metrics (the DLQ producer already uses a -dlq suffix).
      props.put("client.id", config.clientId() + "-producer");
    }
    props.putAll(config.extraProperties());
    return new KafkaProducer<>(props);
  }

  @Override
  public void send(String topic, Object event) {
    // Direct two-argument callers publish on the topic channel.
    send(topic, event, EventBridge.Channel.TOPIC);
  }

  @Override
  public void send(String topic, Object event, EventBridge.Channel channel) {
    // Framework lifecycle events carry internal references (the Container)
    // and are inherently JVM-local — never bridge them.
    if (event.getClass().getName().startsWith("com.jujin.freeway.boot.")) {
      return;
    }
    byte[] bytes;
    try {
      bytes = codec.toJson(event).getBytes(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      // A bridge must not abort the publishing thread: the local dispatch
      // already happened; the remote copy is best-effort by contract.
      LOG.warn("Failed to serialize event for topic '{}' — not bridged", topic, ex);
      return;
    }
    // EventBus.Keyed key -> Kafka record key: per-aggregate ordering on the
    // broker and per-key parallel consumption on the subscriber side.
    String key = (event instanceof EventBus.Keyed k) ? k.key() : null;
    var record = new ProducerRecord<String, byte[]>(topic, key, bytes);
    record
        .headers()
        .add("X-Event-Type", event.getClass().getName().getBytes(StandardCharsets.UTF_8));
    record.headers().add("X-Event-Origin", origin.getBytes(StandardCharsets.UTF_8));
    record.headers().add("X-Event-Channel", channel.name().getBytes(StandardCharsets.UTF_8));
    record
        .headers()
        .add("X-Event-Id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    producer.send(
        record,
        (meta, ex) -> {
          if (ex != null) LOG.warn("Kafka send failed for topic '{}'", topic, ex);
        });
  }

  @Override
  public void close() {
    // Bound the wait: producer.close() without a timeout can block for a
    // very long time when the broker is unreachable.
    producer.close(Duration.ofSeconds(10));
  }
}
