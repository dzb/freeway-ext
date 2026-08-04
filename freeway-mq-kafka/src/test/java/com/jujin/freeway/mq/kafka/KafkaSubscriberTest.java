package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaSubscriberTest {

    @Test
    void consumesAndPublishesMessagesUntilClosed() throws Exception {
        var config = new KafkaConfig(
            "localhost:9092", "test-group", "", "orders", "", "skip");
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var topic = new TopicPartition("orders", 0);

        try (Container container = Freeway.create()) {
            EventBus bus = container.get(EventBus.class);
            var received = new LinkedBlockingQueue<Object>();
            bus.subscribe("orders", received::add);

            var subscriber = new KafkaSubscriber(
                config, bus, new JsonCodecDefault(), consumer);
            consumer.updateBeginningOffsets(Map.of(topic, 0L));
            subscriber.start();
            consumer.rebalance(Set.of(topic));

            consumer.addRecord(new ConsumerRecord<>(
                "orders", 0, 0L, "key-1",
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));

            Object event = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "message should be published to the EventBus");
            assertTrue(event instanceof Map, "untyped messages deserialize as Map");

            subscriber.close();
            assertTrue(consumer.closed(), "consumer should be closed by the poll loop");
        }
    }
}
