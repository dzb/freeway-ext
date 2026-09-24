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

import com.jujin.freeway.cloud.event.EventTrace;
import com.jujin.freeway.commons.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The durable event stream plane: explicit Kafka records with per-key ordering, consumer-group
 * delivery, poison handling and an optional DLQ — entered by name, at the call site.
 *
 * <p>{@link #send} publishes to the topic you name (the record topic IS the routing topic; no
 * bridge override repoints it). {@link #subscribe} registers interest in a topic prefix with a
 * declared payload type: the declared type is also the inbound allowlist — a record matching no
 * subscription is acknowledged and skipped without deserialization, so an undeclared class can
 * never be loaded off the wire. Handler failures are isolated and counted (a consumer bug must not
 * DLQ a healthy record); records that no matching subscription can decode are the poison the
 * retry/DLQ policy moves.
 *
 * <p><b>This plane is separate from the local bus and from the cloud mesh.</b> A sent record is a
 * Kafka record; it is not also a local fact, and an inbound record is not injected into any bus. A
 * fact that must live on two planes is published twice, on purpose. The mesh's at-most-once
 * volatility and Kafka's at-least-once durability are different promises; wanting the durable one
 * is what this class is for.
 *
 * <p><b>At-least-once:</b> broker retries and consumer rebalances can deliver duplicates; consumers
 * that need exactly-once deduplicate by their own business key — the record key ({@code send}'s
 * third argument) is the natural one. Records still carry a CE {@code id} header (fresh per send)
 * and the producer's class name in {@code ce-type} as informational metadata — older subscriber
 * builds route by those, this one ignores them.
 *
 * <p>Lifecycle is owned by the {@link KafkaModule} hook: {@link #start()} begins polling the
 * configured topics (if any), {@link #close()} stops the poller and closes the producer with a
 * bounded wait.
 */
public final class KafkaEvents implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaEvents.class);

  /** The only channel new records carry — the class-channel vocabulary died with the bus bridge. */
  static final String CHANNEL_TOPIC_TOKEN = "topic";

  private final KafkaConfig config;
  private final JsonCodec codec;
  private final Producer<String, byte[]> producer;
  private final String origin;

  /**
   * Runtime interest (this plane's poll set is broker-side group state; no peer needs advance
   * notice, so registration stays open after composition).
   */
  private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

  private final LongAdder sent = new LongAdder();
  private final LongAdder sendFailures = new LongAdder();
  private final LongAdder delivered = new LongAdder();
  private final LongAdder skippedNoSubscription = new LongAdder();
  private final LongAdder handlerFailures = new LongAdder();

  private volatile KafkaSubscriber subscriber;

  public KafkaEvents(KafkaConfig config, JsonCodec codec) {
    this(config, codec, createProducer(config));
  }

  /** Test seam: inject a mock producer. */
  KafkaEvents(KafkaConfig config, JsonCodec codec, Producer<String, byte[]> producer) {
    this.config = Objects.requireNonNull(config, "config");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.producer = Objects.requireNonNull(producer, "producer");
    this.origin = config.origin();
  }

  private static Producer<String, byte[]> createProducer(KafkaConfig config) {
    var props = new java.util.Properties();
    props.put("bootstrap.servers", config.bootstrapServers());
    // Distinct from the consumer's id so producer/consumer are separable in
    // broker metrics (the DLQ producer already uses a -dlq suffix).
    props.put("client.id", config.clientId() + "-producer");
    props.putAll(config.extraProperties());
    return new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer());
  }

  // ==================== publish ====================

  /** Publish a payload to the named Kafka topic (no partition key). */
  public void send(String topic, Object payload) {
    send(topic, payload, null);
  }

  /**
   * Publish with a partition key: the broker keeps records of the same key ordered, consumers
   * parallelize across keys. The key also rides the CE {@code subject} attribute.
   */
  public void send(String topic, Object payload, String key) {
    Objects.requireNonNull(topic, "topic");
    if (payload == null) {
      // A null record value is a Kafka tombstone (compaction deletion marker),
      // not an event — this plane has no signal semantics; skip loudly.
      LOG.warn("Kafka send for topic '{}' has no payload — not sent", topic);
      return;
    }
    byte[] bytes;
    try {
      bytes = codec.toJson(payload).getBytes(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      LOG.warn("Failed to serialize payload for topic '{}' — not sent", topic, ex);
      return;
    }
    var record = new ProducerRecord<String, byte[]>(topic, key, bytes);
    // CloudEvents Kafka binding (see KafkaHeaders): the same logical envelope
    // the WS mesh carries as JSON, here as ce- headers; the record value stays
    // the JSON-encoded payload.
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_SPECVERSION, KafkaHeaders.SPEC_VERSION);
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_ID, UUID.randomUUID().toString());
    // Kafka has no service-registry concept: unlike the mesh's service-based
    // source, this names the sending node (mirroring ce-fworigin).
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_SOURCE, "freeway://" + origin);
    // Informational for legacy consumers; this plane routes by topic and the
    // subscription's declared type, never by a class name off the wire.
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_TYPE, payload.getClass().getName());
    if (key != null && !key.isBlank()) {
      KafkaHeaders.put(record.headers(), KafkaHeaders.CE_SUBJECT, key);
    }
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_TIME, OffsetDateTime.now().toString());
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_DATA_TYPE, KafkaHeaders.DATA_CONTENT_TYPE);
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_CHANNEL, CHANNEL_TOPIC_TOKEN);
    KafkaHeaders.put(record.headers(), KafkaHeaders.CE_ORIGIN, origin);
    // The ambient trace, when the sending thread holds one — same extensions
    // the mesh stamps, here as ce- headers per the Kafka binding.
    for (var entry : EventTrace.injectCurrent().entrySet()) {
      KafkaHeaders.putExtension(record.headers(), entry.getKey(), entry.getValue());
    }
    try {
      producer.send(
          record,
          (meta, ex) -> {
            if (ex != null) {
              sendFailures.increment();
              LOG.warn("Kafka send failed for topic '{}'", topic, ex);
            } else {
              sent.increment();
            }
          });
    } catch (Exception ex) {
      // send() signals some failures synchronously (timeout, illegal state,
      // serializer); the publishing thread must not pay for them.
      sendFailures.increment();
      LOG.warn("Failed to publish record for topic '{}' — not sent", topic, ex);
    }
  }

  // ==================== receive ====================

  /**
   * Register interest in a topic prefix. The payload is deserialized into {@code type} per record —
   * the declaration is the allowlist. Matching is prefix-based, so {@code "orders"} covers {@code
   * orders.created} and {@code orders.shipped}.
   *
   * <p>Handlers run on the poll worker for that key bucket, under the record's restored trace; a
   * throwing handler is isolated and counted.
   */
  public <T> void subscribe(String topicPrefix, Class<T> type, Consumer<T> handler) {
    Objects.requireNonNull(topicPrefix, "topicPrefix");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(handler, "handler");
    subscriptions.add(
        new Subscription(topicPrefix, type, payload -> handler.accept(type.cast(payload))));
    if (config.topics().stream()
        .noneMatch(t -> t.startsWith(topicPrefix) || topicPrefix.isEmpty())) {
      LOG.warn(
          "Kafka subscription for prefix '{}' matches none of the polled topics {}"
              + " — it will receive nothing",
          topicPrefix,
          config.topics());
    }
  }

  /** One polled record into this plane: match, decode, deliver. Poison throws. */
  void handle(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record) {
    List<Subscription> hits = new ArrayList<>();
    for (Subscription sub : subscriptions) {
      if (sub.prefix().isEmpty() || record.topic().startsWith(sub.prefix())) {
        hits.add(sub);
      }
    }
    if (hits.isEmpty()) {
      skippedNoSubscription.increment();
      LOG.debug(
          "No kafka subscription for topic '{}' — acknowledged without reading", record.topic());
      return;
    }
    // The inbound trace, restored around decode and delivery so handlers
    // observe the sender's causality. Absent runs bare — a traceless record
    // must not clear the poll worker's ambient.
    Map<String, String> trace = new LinkedHashMap<>();
    String traceparent = KafkaHeaders.read(record.headers(), KafkaHeaders.CE_TRACEPARENT, null);
    String tracestate = KafkaHeaders.read(record.headers(), KafkaHeaders.CE_TRACESTATE, null);
    if (traceparent != null) trace.put(EventTrace.TRACEPARENT, traceparent);
    if (tracestate != null) trace.put(EventTrace.TRACESTATE, tracestate);

    String json = new String(record.value(), StandardCharsets.UTF_8);
    Exception decodeFailure = null;
    int deliverable = 0;
    for (Subscription sub : hits) {
      Object payload;
      try {
        payload = codec.fromJson(json, sub.type());
      } catch (Exception ex) {
        // Undecodable for the declared type: if nobody can read it, the
        // record itself is poison (retry → DLQ by policy).
        decodeFailure = ex;
        continue;
      }
      deliverable++;
      final Object delivered0 = payload;
      try {
        EventTrace.runWithTrace(trace, () -> sub.handler().accept(delivered0));
        delivered.increment();
      } catch (RuntimeException ex) {
        // A handler failure is a consumer bug, not a record defect — the
        // record already decoded; neither retry nor DLQ would fix it.
        handlerFailures.increment();
        LOG.warn(
            "Kafka handler failed for topic '{}' (prefix '{}')", record.topic(), sub.prefix(), ex);
      }
    }
    if (deliverable == 0 && decodeFailure != null) {
      throw new RuntimeException(
          "No subscription could decode the record: " + decodeFailure.getMessage(), decodeFailure);
    }
  }

  // ==================== lifecycle ====================

  /** Begin polling the configured topics (no-op when none are set). */
  public void start() {
    if (subscriber == null) {
      subscriber =
          new KafkaSubscriber(
              config,
              this,
              KafkaSubscriber.createConsumer(config),
              KafkaSubscriber.createDlqProducer(config));
    }
    subscriber.start();
  }

  /** This plane's counters — kept separate from every other plane's books. */
  public KafkaStats stats() {
    return new KafkaStats(
        sent.sum(),
        sendFailures.sum(),
        delivered.sum(),
        skippedNoSubscription.sum(),
        handlerFailures.sum());
  }

  /**
   * @param sent records the broker acknowledged
   * @param sendFailures send outcomes that were logged and dropped
   * @param delivered successful handler invocations
   * @param skippedNoSubscription polled records matching no subscription
   * @param handlerFailures throwing handlers (isolated, never poison)
   */
  public record KafkaStats(
      long sent,
      long sendFailures,
      long delivered,
      long skippedNoSubscription,
      long handlerFailures) {}

  private record Subscription(String prefix, Class<?> type, Consumer<Object> handler) {}

  @Override
  public void close() {
    KafkaSubscriber s = subscriber;
    if (s != null) {
      s.close();
    }
    // Bound the wait: producer.close() without a timeout can block for a
    // very long time when the broker is unreachable.
    producer.close(java.time.Duration.ofSeconds(10));
  }
}
