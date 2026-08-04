package com.jujin.freeway.mq.kafka;

import com.jujin.freeway.ioc.annotation.Value;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for the Kafka adapter, resolved from the config cascade via
 * {@code @Value} (e.g. {@code freeway.kafka.bootstrap-servers}).
 */
public record KafkaConfig(
    @Value("${freeway.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
    @Value("${freeway.kafka.group-id:freeway}") String groupId,
    @Value("${freeway.kafka.client-id:}") String clientId,
    @Value("${freeway.kafka.topics:}") String topicsRaw,
    @Value("${freeway.kafka.allowed-event-types:}") String allowedEventTypesRaw,
    @Value("${freeway.kafka.poison-policy:skip}") String poisonPolicy
) {
    public KafkaConfig {
        if (poisonPolicy == null || poisonPolicy.isBlank()
                || !"skip".equalsIgnoreCase(poisonPolicy.trim())
                && !"fail".equalsIgnoreCase(poisonPolicy.trim())) {
            throw new IllegalArgumentException(
                "freeway.kafka.poison-policy must be 'skip' or 'fail', got: '"
                    + poisonPolicy + "'");
        }
    }

    public List<String> topics() {
        if (topicsRaw == null || topicsRaw.isBlank()) return List.of();
        return Arrays.stream(topicsRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Comma-separated allowlist of event types that may be deserialized from
     * incoming messages. When empty, only plain {@code Map} payloads (messages
     * without an {@code X-Event-Type} header) are accepted. Typed messages
     * whose type is not listed are rejected instead of being deserialized into
     * arbitrary classes from the classpath.
     */
    public Set<String> allowedEventTypes() {
        if (allowedEventTypesRaw == null || allowedEventTypesRaw.isBlank()) return Set.of();
        return Arrays.stream(allowedEventTypesRaw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Poison-message policy: {@code skip} (default) logs an error and continues,
     * {@code fail} stops the subscriber so the offset is not committed.
     */
    public boolean failOnPoison() {
        return poisonPolicy != null && "fail".equalsIgnoreCase(poisonPolicy.trim());
    }
}
