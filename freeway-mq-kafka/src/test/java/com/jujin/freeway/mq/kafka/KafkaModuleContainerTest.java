package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KafkaModuleContainerTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("freeway.kafka.bootstrap-servers");
        System.clearProperty("freeway.kafka.group-id");
        System.clearProperty("freeway.kafka.topics");
        System.clearProperty("freeway.kafka.allowed-event-types");
        System.clearProperty("freeway.kafka.poison-policy");
    }

    @Test
    void injectsKafkaConfigFromSystemProperties() {
        System.setProperty("freeway.kafka.bootstrap-servers", "kafka-test:9092");
        System.setProperty("freeway.kafka.group-id", "container-test");
        System.setProperty("freeway.kafka.topics", "orders, payments");
        System.setProperty("freeway.kafka.allowed-event-types", "com.acme.OrderCreated");
        System.setProperty("freeway.kafka.poison-policy", "fail");

        try (Container container = Freeway.create(new KafkaModule())) {
            KafkaConfig config = container.get(KafkaConfig.class);
            assertEquals("kafka-test:9092", config.bootstrapServers());
            assertEquals("container-test", config.groupId());
            assertEquals(List.of("orders", "payments"), config.topics());
            assertEquals(Set.of("com.acme.OrderCreated"), config.allowedEventTypes());
            assertTrue(config.failOnPoison());
        }
    }
}
