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

import com.jujin.freeway.cloud.event.EventOrigin;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.CloseOptions;
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
 * The poll machinery of the durable stream plane: consumes configured Kafka topics and hands each
 * record to {@link KafkaEvents#handle} — matching subscriptions, decoding, delivery and the trace
 * context are the plane's business; this class owns the consumer thread, offset commits,
 * key-bucket parallelism, retry backoff, poison policy and the DLQ.
 *
 * <p><b>Internal machinery</b> — applications enter through {@link KafkaEvents#subscribe} and the
 * module hook, not here. The declared subscription types are the inbound allowlist: a record
 * matching no subscription is acknowledged and skipped without being read at all.</p>
 */
final class KafkaSubscriber implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaSubscriber.class);
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  /** Upper bound for a single retry backoff, guarding against shift overflow. */
  private static final long MAX_RETRY_BACKOFF_MS = 60_000L;

  private final Consumer<String, byte[]> consumer;
  private final KafkaEvents plane;
  private final KafkaConfig config;
  private final List<String> topics;
  private final Producer<String, byte[]> dlqProducer;
  private final ExecutorService executor;
  private final int concurrency;

  /** This node's identity on the wire; used to skip its own re-broadcast events. */
  private final String origin;

  private final boolean suppressOwn;
  private volatile boolean running;
  private volatile Thread pollThread;

  /**
   * Machinery constructor — {@link KafkaEvents#start()} builds the real one; tests inject a mock
   * consumer and DLQ producer.
   */
  KafkaSubscriber(
      KafkaConfig config,
      KafkaEvents plane,
      Consumer<String, byte[]> consumer,
      Producer<String, byte[]> dlqProducer) {
    this.config = config;
    this.plane = plane;
    // Parse once here — this runs per message on the hot path otherwise.
    this.topics = config.topics();
    this.consumer = consumer;
    this.dlqProducer = dlqProducer;
    this.concurrency = config.concurrency();
    this.origin = config.origin();
    this.suppressOwn = config.suppressOwn();
    // Named daemon workers: an unclosed subscriber must never keep the JVM
    // alive, and unnamed pool threads are undebuggable in a thread dump.
    this.executor =
        concurrency > 1
            ? Executors.newFixedThreadPool(
                concurrency,
                Thread.ofPlatform().daemon().name("freeway-kafka-worker-", 0).factory())
            : null;
  }

  static KafkaConsumer<String, byte[]> createConsumer(KafkaConfig config) {
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

  static Producer<String, byte[]> createDlqProducer(KafkaConfig config) {
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

  void start() {
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
        consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
      } catch (Exception ex) {
        LOG.warn("Kafka consumer close failed", ex);
      }
    }
  }

  /** Processes a poll batch, optionally parallelizing across keys. */
  private void processBatch(Iterable<ConsumerRecord<String, byte[]>> records) throws Exception {
    List<ConsumerRecord<String, byte[]>> batch = new ArrayList<>();
    records.forEach(batch::add);
    if (executor == null || batch.size() <= 1) {
      for (var record : batch) {
        if (!running) {
          // Shutdown raced with an already-returned batch; do not
          // deliver into a closing plane.
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
    return key == null ? 0 : Math.floorMod(key.hashCode(), concurrency);
  }

  private boolean processWithPolicy(ConsumerRecord<String, byte[]> record) {
    if (isTombstone(record)) {
      // Kafka tombstone (null value) — a deletion marker, not an event.
      // Skip it without retrying; the batch commit acknowledges it.
      LOG.debug("Skipping tombstone at '{}' offset {}", record.topic(), record.offset());
      return true;
    }
    if (suppressOwn && isOwnEvent(record)) {
      // This node's own re-broadcast event (published locally, sent out,
      // and consumed back by the same group): local subscribers already
      // received it at publish time. Skip to avoid duplicate local delivery.
      // Not a failure — acknowledge so the offset is committed.
      LOG.debug(
          "Skipping own event at '{}' offset {} (origin {})",
          record.topic(),
          record.offset(),
          origin);
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
      // The DLQ preserved the record; the policy still decides whether this
      // subscriber keeps going (README: moved to the DLQ first, policy second).
      if (config.failOnPoison()) {
        LOG.error(
            "Poison message at '{}' offset {} moved to DLQ '{}'; stopping per policy",
            record.topic(),
            record.offset(),
            config.dlqTopic(),
            cause);
        return false;
      }
      LOG.error(
          "Poison message at '{}' offset {} moved to DLQ '{}'",
          record.topic(),
          record.offset(),
          config.dlqTopic(),
          cause);
      return true;
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
    out.headers()
        .add(KafkaHeaders.DLQ_ORIGINAL_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8));
    out.headers()
        .add(
            KafkaHeaders.DLQ_ORIGINAL_OFFSET,
            String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8));
    String reason =
        cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause);
    out.headers().add(KafkaHeaders.DLQ_REASON, reason.getBytes(StandardCharsets.UTF_8));
    dlqProducer.send(out).get(10, TimeUnit.SECONDS);
  }

  private void processRecord(ConsumerRecord<String, byte[]> record) {
    // The plane owns trace restoration, subscription matching, decoding and
    // delivery; a throwing handler is isolated there, and a record that no
    // matching subscription can decode surfaces as an exception — the retry
    // and poison policy above moves it (DLQ by config).
    plane.handle(record);
  }

  /** True when this record was published by this node (its origin header matches ours). */
  private boolean isOwnEvent(ConsumerRecord<String, byte[]> record) {
    return EventOrigin.isOwn(
        origin,
        KafkaHeaders.read(record.headers(), KafkaHeaders.CE_ORIGIN, KafkaHeaders.LEGACY_ORIGIN));
  }

  /** Returns true when the record is a Kafka tombstone (deletion marker). */
  static boolean isTombstone(ConsumerRecord<String, byte[]> record) {
    return record.value() == null;
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
      // timeout while the loop still delivers into a closing plane
      // and may hit the already-closed DLQ producer.
      thread.interrupt();
    }
    if (thread == null) {
      // Never started (e.g. no topics configured) — safe to close here.
      consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
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
