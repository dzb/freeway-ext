package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.commons.defer.Defer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.EventBus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

public class KafkaSubscriber implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSubscriber.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final EventBus bus;
    private final JsonCodec codec;
    private final KafkaConfig config;
    private volatile boolean running;
    private volatile Thread pollThread;

    public KafkaSubscriber(KafkaConfig config, EventBus bus) {
        this(config, bus, new JsonCodecDefault());
    }

    public KafkaSubscriber(KafkaConfig config, EventBus bus, JsonCodec codec) {
        this.config = config;
        this.bus = bus;
        this.codec = codec;
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
        if (config.topics().isEmpty()) return;
        running = true;
        consumer.subscribe(config.topics());
        pollThread = Thread.ofVirtual().name("freeway-kafka-subscriber").start(this::pollLoop);
        LOG.info("Kafka subscriber started for topics: {}", config.topics());
    }

    private void pollLoop() {
        while (running) {
            try {
                var records = consumer.poll(Duration.ofSeconds(1));
                for (var record : records) {
                    try {
                        processRecord(record);
                    } catch (Exception e) {
                        LOG.warn("First attempt failed for '{}' at offset {}; retrying once",
                            record.topic(), record.offset(), e);
                        try {
                            processRecord(record);
                        } catch (Exception e2) {
                            LOG.warn("Retry failed for '{}' at offset {}; skipping",
                                record.topic(), record.offset(), e2);
                        }
                    }
                }
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            } catch (Exception e) {
                if (running) LOG.warn("Kafka poll or commit failed; will retry", e);
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
        if (typeName != null) {
            try {
                Class<?> type = Class.forName(typeName);
                return codec.fromJson(json, type);
            } catch (ClassNotFoundException e) {
                LOG.debug("Event type not found on classpath: {}, falling back to Map", typeName);
            }
        }
        return codec.fromJson(json, Map.class);
    }

    private String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        try {
            if (pollThread != null) {
                pollThread.join(Duration.ofSeconds(5).toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        consumer.close();
        LOG.info("Kafka subscriber stopped");
    }
}
