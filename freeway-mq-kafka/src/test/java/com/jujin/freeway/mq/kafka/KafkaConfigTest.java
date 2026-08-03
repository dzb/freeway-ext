package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KafkaConfigTest {

    private static KafkaConfig config(String topics, String allowed, String policy) {
        return new KafkaConfig("localhost:9092", "test-group", topics, allowed, policy);
    }

    @Test
    void topicsParsesCommaSeparatedList() {
        assertEquals(List.of(), config("", "", "skip").topics());
        assertEquals(List.of(), config(null, "", "skip").topics());
        assertEquals(List.of("orders", "payments"),
            config(" orders , payments ", "", "skip").topics());
    }

    @Test
    void allowedEventTypesDefaultsToEmptyAllowlist() {
        assertTrue(config("orders", "", "skip").allowedEventTypes().isEmpty());
        assertTrue(config("orders", null, "skip").allowedEventTypes().isEmpty());
        assertEquals(Set.of("com.acme.OrderCreated", "com.acme.PaymentReceived"),
            config("orders", "com.acme.OrderCreated, com.acme.PaymentReceived", "skip")
                .allowedEventTypes());
    }

    @Test
    void poisonPolicyParsing() {
        assertFalse(config("orders", "", "skip").failOnPoison());
        assertFalse(config("orders", "", "SKIP").failOnPoison());
        assertTrue(config("orders", "", "fail").failOnPoison());
        assertTrue(config("orders", "", "FAIL").failOnPoison());
    }
}
