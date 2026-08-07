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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EventBridge} that publishes Freeway events to Kafka topics. Events are serialized as JSON
 * with an {@code X-Event-Type} header carrying the concrete class name.
 */
public class KafkaEventBridge implements EventBridge, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaEventBridge.class);

  private final Producer<String, byte[]> producer;
  private final JsonCodec codec;

  public KafkaEventBridge(KafkaConfig config) {
    this(config, new JsonCodecDefault());
  }

  public KafkaEventBridge(KafkaConfig config, JsonCodec codec) {
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
    this.producer = new KafkaProducer<>(props);
    this.codec = codec;
  }

  @Override
  public void send(String topic, Object event) {
    byte[] bytes;
    try {
      bytes = codec.toJson(event).getBytes(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new RuntimeException("Failed to serialize event for topic '" + topic + "'", ex);
    }
    var record = new ProducerRecord<String, byte[]>(topic, null, bytes);
    record
        .headers()
        .add("X-Event-Type", event.getClass().getName().getBytes(StandardCharsets.UTF_8));
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
