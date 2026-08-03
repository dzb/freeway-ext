package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.EventBus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class KafkaSubscriber implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSubscriber.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaConsumer<String, byte[]> consumer;
    private final EventBus bus;
    private final JsonCodec codec;
    private final KafkaConfig config;
    private final List<String> topics;
    private final Set<String> allowedEventTypes;
    private volatile boolean running;
    private volatile Thread pollThread;

    public KafkaSubscriber(KafkaConfig config, EventBus bus) {
        this(config, bus, new JsonCodecDefault());
    }

    public KafkaSubscriber(KafkaConfig config, EventBus bus, JsonCodec codec) {
        this.config = config;
        this.bus = bus;
        this.codec = codec;
        // Parse once here — this runs per message on the hot path otherwise.
        this.topics = config.topics();
        this.allowedEventTypes = config.allowedEventTypes();
        var props = new Properties();
        props.put("bootstrap.servers", config.bootstrapServers());
        props.put("group.id", config.groupId());
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        props.put("enable.auto.commit", "false");
        props.put("auto.offset.reset", "earliest");
        this.consumer = new KafkaConsumer<>(props);
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
                    for (var record : records) {
                        if (!processWithRetry(record) && running) {
                            // fail policy: stop without committing so the broker
                            // redelivers from this offset on the next start.
                            running = false;
                            break;
                        }
                    }
                    if (!records.isEmpty() && running) {
                        consumer.commitSync(COMMIT_TIMEOUT);
                    }
                } catch (WakeupException ex) {
                    // Normal wakeup triggered by close().
                    if (running) LOG.debug("Kafka poll loop woken up", ex);
                } catch (Exception e) {
                    if (running) LOG.warn("Kafka poll or commit failed; will retry", e);
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

    private boolean processWithRetry(ConsumerRecord<String, byte[]> record) {
        if (isTombstone(record)) {
            // Kafka tombstone (null value) — a deletion marker, not an event.
            // Skip it without retrying; the batch commit acknowledges it.
            LOG.debug("Skipping tombstone at '{}' offset {}", record.topic(), record.offset());
            return true;
        }
        try {
            processRecord(record);
            return true;
        } catch (Exception e) {
            LOG.warn("First attempt failed for '{}' at offset {}; retrying once",
                record.topic(), record.offset(), e);
            try {
                processRecord(record);
                return true;
            } catch (Exception e2) {
                if (config.failOnPoison()) {
                    LOG.error("Poison message at '{}' offset {}; stopping subscriber per policy",
                        record.topic(), record.offset(), e2);
                    return false;
                }
                LOG.error("Poison message at '{}' offset {}; skipping per policy",
                    record.topic(), record.offset(), e2);
                return true;
            }
        }
    }

    private void processRecord(ConsumerRecord<String, byte[]> record) {
        Defer.within(() -> {
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
     * Resolves the declared event type against the configured allowlist.
     * Messages without a type header are treated as {@code Map}; typed
     * messages are rejected unless the exact class name is allowed.
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
            throw new IllegalArgumentException(
                "Event type not found on classpath: " + trimmed, ex);
        }
    }

    /** Returns true when the record is a Kafka tombstone (deletion marker). */
    static boolean isTombstone(ConsumerRecord<String, byte[]> record) {
        return record.value() == null;
    }

    private String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        Thread thread = pollThread;
        if (thread == null) {
            // Never started (e.g. no topics configured) — safe to close here.
            consumer.close(CLOSE_TIMEOUT);
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
            // block once it exits. Closing here would race with poll().
            LOG.warn("Kafka poll thread did not stop in time; consumer will be "
                + "closed when the poll loop exits");
            return;
        }
        LOG.info("Kafka subscriber stopped");
    }
}
