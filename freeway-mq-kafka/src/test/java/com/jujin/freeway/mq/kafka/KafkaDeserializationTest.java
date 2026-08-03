package com.jujin.freeway.mq.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaDeserializationTest {

    @Test
    void untypedMessageDeserializesAsMap() {
        assertEquals(Map.class, KafkaSubscriber.resolveEventType(null, Set.of()));
        assertEquals(Map.class, KafkaSubscriber.resolveEventType("  ", Set.of()));
    }

    @Test
    void allowedTypedMessageUsesDeclaredType() {
        assertEquals(String.class,
            KafkaSubscriber.resolveEventType("java.lang.String",
                Set.of("java.lang.String")));
    }

    @Test
    void disallowedTypeIsRejected() {
        assertThrows(SecurityException.class,
            () -> KafkaSubscriber.resolveEventType("java.lang.Runtime",
                Set.of("java.lang.String")));
        assertThrows(SecurityException.class,
            () -> KafkaSubscriber.resolveEventType("java.lang.Runtime", Set.of()));
    }

    @Test
    void allowedButMissingTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> KafkaSubscriber.resolveEventType("com.acme.DoesNotExist",
                Set.of("com.acme.DoesNotExist")));
    }

    @Test
    void tombstoneRecordIsDetected() {
        var tombstone = new ConsumerRecord<String, byte[]>(
            "orders", 0, 5L, "key-1", null);
        var normal = new ConsumerRecord<String, byte[]>(
            "orders", 0, 5L, "key-1", new byte[] {1});
        assertTrue(KafkaSubscriber.isTombstone(tombstone));
        assertFalse(KafkaSubscriber.isTombstone(normal));
    }
}
