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
import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.EventBus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes Freeway events from Kafka topics and publishes them on the {@link EventBus}.
 * Deserialization is restricted to the configured allowlist; poison messages follow the configured
 * policy.
 */
public class KafkaSubscriber implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaSubscriber.class);
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  /** Upper bound for a single retry backoff, guarding against shift overflow. */
  private static final long MAX_RETRY_BACKOFF_MS = 60_000L;

  private final Consumer<String, byte[]> consumer;
  private final EventBus bus;
  private final JsonCodec codec;
  private final KafkaConfig config;
  private final List<String> topics;
  private final Set<String> allowedEventTypes;
  private final Producer<String, byte[]> dlqProducer;
  private final ExecutorService executor;
  private final int concurrency;
  private volatile boolean running;
  private volatile Thread pollThread;

  public KafkaSubscriber(KafkaConfig config, EventBus bus) {
    this(config, bus, new JsonCodecDefault());
  }

  public KafkaSubscriber(KafkaConfig config, EventBus bus, JsonCodec codec) {
    this(config, bus, codec, createConsumer(config), createDlqProducer(config));
  }

  /** Test seam: allows injecting a mock consumer and DLQ producer. */
  KafkaSubscriber(
      KafkaConfig config,
      EventBus bus,
      JsonCodec codec,
      Consumer<String, byte[]> consumer,
      Producer<String, byte[]> dlqProducer) {
    this.config = config;
    this.bus = bus;
    this.codec = codec;
    // Parse once here — this runs per message on the hot path otherwise.
    this.topics = config.topics();
    this.allowedEventTypes = config.allowedEventTypes();
    this.consumer = consumer;
    this.dlqProducer = dlqProducer;
    this.concurrency = config.concurrency();
    this.executor = concurrency > 1 ? Executors.newFixedThreadPool(concurrency) : null;
  }

  private static KafkaConsumer<String, byte[]> createConsumer(KafkaConfig config) {
    var props = new Properties();
    props.put("bootstrap.servers", config.bootstrapServers());
    props.put("group.id", config.groupId());
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", ByteArrayDeserializer.class.getName());
    props.put("enable.auto.commit", "false");
    props.put("auto.offset.reset", "earliest");
    if (config.clientId() != null && !config.clientId().isBlank()) {
      props.put("client.id", config.clientId());
    }
    props.putAll(config.extraProperties());
    return new KafkaConsumer<>(props);
  }

  private static Producer<String, byte[]> createDlqProducer(KafkaConfig config) {
    if (!config.dlqEnabled()) {
      return null;
    }
    var props = new Properties();
    props.put("bootstrap.servers", config.bootstrapServers());
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", ByteArraySerializer.class.getName());
    if (config.clientId() != null && !config.clientId().isBlank()) {
      props.put("client.id", config.clientId() + "-dlq");
    }
    props.putAll(config.extraProperties());
    return new KafkaProducer<>(props);
  }

  public void start() {
    if (topics.isEmpty()) return;
    running = true;
    consumer.subscribe(topics);
    pollThread = Thread.ofVirtual().name("freeway-kafka-subscriber").start(this::pollLoop);
    LOG.info("Kafka subscriber started for topics: {}", topics);
  }

  private void pollLoop() {
    try {
      while (running) {
        try {
          var records = consumer.poll(POLL_TIMEOUT);
          if (!records.isEmpty() && running) {
            processBatch(records);
          }
          if (!records.isEmpty() && running) {
            consumer.commitSync(COMMIT_TIMEOUT);
          }
        } catch (WakeupException ex) {
          // Normal wakeup triggered by close().
          if (running) LOG.debug("Kafka poll loop woken up", ex);
        } catch (Exception e) {
          if (running) {
            LOG.warn("Kafka poll or commit failed; will retry", e);
            // Bounded pause so a persistently failing batch (or transient
            // poll errors) does not busy-spin on the same uncommitted records.
            try {
              Thread.sleep(POLL_TIMEOUT.toMillis());
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              running = false;
            }
          }
        }
      }
    } finally {
      // The consumer is only ever closed from this thread; close() never
      // touches it concurrently (KafkaConsumer is not thread-safe).
      try {
        consumer.close(CLOSE_TIMEOUT);
      } catch (Exception ex) {
        LOG.warn("Kafka consumer close failed", ex);
      }
    }
  }

  /** Processes a poll batch, optionally parallelizing across keys. */
  private void processBatch(Iterable<ConsumerRecord<String, byte[]>> records) throws Exception {
    List<ConsumerRecord<String, byte[]>> batch = new ArrayList<>();
    for (var record : records) {
      batch.add(record);
    }
    if (executor == null || batch.size() <= 1) {
      for (var record : batch) {
        if (!running) {
          // Shutdown raced with an already-returned batch; do not
          // publish into a closing EventBus.
          break;
        }
        if (!processWithPolicy(record)) {
          running = false;
          break;
        }
      }
      return;
    }

    // Bucket by key hash so records sharing a key stay ordered, while
    // different keys are processed in parallel.
    List<List<ConsumerRecord<String, byte[]>>> buckets = new ArrayList<>(concurrency);
    for (int i = 0; i < concurrency; i++) {
      buckets.add(new ArrayList<>());
    }
    for (var record : batch) {
      int bucket = keyBucket(record.key());
      buckets.get(bucket).add(record);
    }

    List<Future<?>> futures = new ArrayList<>(concurrency);
    for (var bucket : buckets) {
      if (!bucket.isEmpty()) {
        futures.add(
            executor.submit(
                () -> {
                  for (var record : bucket) {
                    if (!running) {
                      break;
                    }
                    if (!processWithPolicy(record)) {
                      running = false;
                      break;
                    }
                  }
                  return null;
                }));
      }
    }
    for (var future : futures) {
      // Bucket tasks swallow per-record exceptions; an unexpected error
      // still surfaces here so the batch is not committed.
      future.get();
    }
  }

  private int keyBucket(String key) {
    return key == null ? 0 : (key.hashCode() & Integer.MAX_VALUE) % concurrency;
  }

  private boolean processWithPolicy(ConsumerRecord<String, byte[]> record) {
    if (isTombstone(record)) {
      // Kafka tombstone (null value) — a deletion marker, not an event.
      // Skip it without retrying; the batch commit acknowledges it.
      LOG.debug("Skipping tombstone at '{}' offset {}", record.topic(), record.offset());
      return true;
    }
    int attempts = config.maxRetries() + 1;
    Exception lastFailure = null;
    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        processRecord(record);
        return true;
      } catch (Exception ex) {
        lastFailure = ex;
        if (attempt + 1 < attempts) {
          // Cap the exponential backoff: an uncapped shift overflows long for
          // large max-retries (negative sleep -> IllegalArgumentException that
          // escapes the retry loop) and long cumulative sleeps exceed
          // max.poll.interval.ms, kicking the consumer from the group.
          long backoff =
              Math.min(
                  config.retryBackoffMs() * (1L << Math.min(attempt, 20)), MAX_RETRY_BACKOFF_MS);
          LOG.warn(
              "Attempt {} of {} failed for '{}' at offset {}; retrying in {} ms",
              attempt + 1,
              attempts,
              record.topic(),
              record.offset(),
              backoff,
              ex);
          try {
            Thread.sleep(backoff);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
          }
        }
      }
    }
    return handlePoison(record, lastFailure);
  }

  private boolean handlePoison(ConsumerRecord<String, byte[]> record, Exception cause) {
    if (config.dlqEnabled()) {
      try {
        sendToDlq(record, cause);
        LOG.error(
            "Poison message at '{}' offset {} moved to DLQ '{}'",
            record.topic(),
            record.offset(),
            config.dlqTopic(),
            cause);
        return true;
      } catch (Exception dlqFailure) {
        // The message was neither processed nor moved to the DLQ; committing
        // would lose it permanently. Stop without committing so the offset is
        // redelivered until the DLQ accepts the record (at-least-once).
        LOG.error(
            "DLQ send failed for poison message at '{}' offset {}; not committing so "
                + "it can be redelivered",
            record.topic(),
            record.offset(),
            dlqFailure);
        return false;
      }
    }
    if (config.failOnPoison()) {
      LOG.error(
          "Poison message at '{}' offset {}; stopping subscriber per policy",
          record.topic(),
          record.offset(),
          cause);
      return false;
    }
    LOG.error(
        "Poison message at '{}' offset {}; skipping per policy",
        record.topic(),
        record.offset(),
        cause);
    return true;
  }

  private void sendToDlq(ConsumerRecord<String, byte[]> record, Exception cause) throws Exception {
    if (dlqProducer == null) {
      throw new IllegalStateException("DLQ topic configured but no producer available");
    }
    var out = new ProducerRecord<>(config.dlqTopic(), record.key(), record.value());
    record.headers().forEach(header -> out.headers().add(header.key(), header.value()));
    out.headers().add("X-DLQ-Original-Topic", record.topic().getBytes(StandardCharsets.UTF_8));
    out.headers()
        .add(
            "X-DLQ-Original-Offset",
            String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8));
    String reason =
        cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
    out.headers().add("X-DLQ-Reason", reason.getBytes(StandardCharsets.UTF_8));
    dlqProducer.send(out).get(10, TimeUnit.SECONDS);
  }

  private void processRecord(ConsumerRecord<String, byte[]> record) {
    Defer.within(
        () -> {
          Object event;
          try {
            event = deserialize(record);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          bus.publish(record.topic(), event);
        });
  }

  private Object deserialize(ConsumerRecord<String, byte[]> record) throws Exception {
    String json = new String(record.value(), StandardCharsets.UTF_8);
    String typeName = header(record, "X-Event-Type");
    Class<?> type = resolveEventType(typeName, allowedEventTypes);
    return codec.fromJson(json, type);
  }

  /**
   * Resolves the declared event type against the configured allowlist. Messages without a type
   * header are treated as {@code Map}; typed messages are rejected unless the exact class name is
   * allowed.
   */
  static Class<?> resolveEventType(String typeName, Set<String> allowed) {
    if (typeName == null || typeName.isBlank()) {
      return Map.class;
    }
    String trimmed = typeName.trim();
    if (!allowed.contains(trimmed)) {
      throw new SecurityException(
          "Event type is not in freeway.kafka.allowed-event-types: " + trimmed);
    }
    try {
      return Class.forName(trimmed);
    } catch (ClassNotFoundException ex) {
      throw new IllegalArgumentException("Event type not found on classpath: " + trimmed, ex);
    }
  }

  /** Returns true when the record is a Kafka tombstone (deletion marker). */
  static boolean isTombstone(ConsumerRecord<String, byte[]> record) {
    return record.value() == null;
  }

  private String header(ConsumerRecord<String, byte[]> record, String name) {
    var header = record.headers().lastHeader(name);
    // Kafka allows null header values; treat them as absent.
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    running = false;
    consumer.wakeup();
    Thread thread = pollThread;
    if (thread != null) {
      // Interrupt the poll loop so it cannot linger in a bounded backoff
      // sleep (up to 60 s), commitSync (10 s), or DLQ send (10 s) after
      // close(); both interrupt handlers terminate cleanly without
      // committing. Without this, close() would return after the join
      // timeout while the loop still publishes into a closing EventBus
      // and may hit the already-closed DLQ producer.
      thread.interrupt();
    }
    if (thread == null) {
      // Never started (e.g. no topics configured) — safe to close here.
      consumer.close(CLOSE_TIMEOUT);
      closeResources();
      LOG.info("Kafka subscriber stopped");
      return;
    }
    try {
      thread.join(CLOSE_TIMEOUT.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) {
      // The poll loop still owns the consumer; it closes it in its finally
      // block once it exits (closing here would race with poll()). Release
      // the producer and executor anyway: the poll thread can legitimately
      // outlive the join window (bounded commitSync/consumer close), and
      // leaked non-daemon executor or producer threads would keep the JVM
      // alive at shutdown.
      LOG.warn(
          "Kafka poll thread did not stop in time; consumer will be "
              + "closed when the poll loop exits");
      closeResources();
      return;
    }
    closeResources();
    LOG.info("Kafka subscriber stopped");
  }

  private void closeResources() {
    closeDlqProducer();
    closeExecutor();
  }

  private void closeDlqProducer() {
    if (dlqProducer != null) {
      try {
        dlqProducer.close(Duration.ofSeconds(10));
      } catch (Exception ex) {
        LOG.warn("Kafka DLQ producer close failed", ex);
      }
    }
  }

  private void closeExecutor() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}
