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

import com.jujin.freeway.ioc.annotation.Value;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for the Kafka adapter, resolved from the config cascade via {@code @Value} (e.g.
 * {@code freeway.kafka.bootstrap-servers}).
 */
public record KafkaConfig(
    @Value("${freeway.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
    @Value("${freeway.kafka.group-id:freeway}") String groupId,
    @Value("${freeway.kafka.client-id:}") String clientId,
    @Value("${freeway.kafka.topics:}") String topicsRaw,
    @Value("${freeway.kafka.allowed-event-types:}") String allowedEventTypesRaw,
    @Value("${freeway.kafka.poison-policy:skip}") String poisonPolicy,
    @Value("${freeway.kafka.properties:}") String propertiesRaw,
    @Value("${freeway.kafka.dlq-topic:}") String dlqTopic,
    @Value("${freeway.kafka.max-retries:1}") int maxRetries,
    @Value("${freeway.kafka.retry-backoff-ms:1000}") long retryBackoffMs,
    @Value("${freeway.kafka.concurrency:1}") int concurrency) {
  public KafkaConfig {
    if (!isValidPoisonPolicy(poisonPolicy)) {
      throw new IllegalArgumentException(
          "freeway.kafka.poison-policy must be 'skip' or 'fail', got: '" + poisonPolicy + "'");
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException(
          "freeway.kafka.max-retries must be >= 0, got: " + maxRetries);
    }
    if (retryBackoffMs < 0) {
      throw new IllegalArgumentException(
          "freeway.kafka.retry-backoff-ms must be >= 0, got: " + retryBackoffMs);
    }
    if (concurrency < 1) {
      throw new IllegalArgumentException(
          "freeway.kafka.concurrency must be >= 1, got: " + concurrency);
    }
  }

  private static boolean isValidPoisonPolicy(String policy) {
    if (policy == null || policy.isBlank()) {
      return false;
    }
    String trimmed = policy.trim();
    return "skip".equalsIgnoreCase(trimmed) || "fail".equalsIgnoreCase(trimmed);
  }

  public List<String> topics() {
    if (topicsRaw == null || topicsRaw.isBlank()) return List.of();
    return Arrays.stream(topicsRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /**
   * Comma-separated allowlist of event types that may be deserialized from incoming messages. When
   * empty, only plain {@code Map} payloads (messages without an {@code X-Event-Type} header) are
   * accepted. Typed messages whose type is not listed are rejected instead of being deserialized
   * into arbitrary classes from the classpath.
   */
  public Set<String> allowedEventTypes() {
    if (allowedEventTypesRaw == null || allowedEventTypesRaw.isBlank()) return Set.of();
    return Arrays.stream(allowedEventTypesRaw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Poison-message policy: {@code skip} (default) logs an error and continues, {@code fail} stops
   * the subscriber so the offset is not committed.
   */
  public boolean failOnPoison() {
    return poisonPolicy != null && "fail".equalsIgnoreCase(poisonPolicy.trim());
  }

  /**
   * Extra Kafka client properties in {@code key=value} pairs separated by semicolons (e.g. {@code
   * security.protocol=SASL_SSL;sasl.mechanism=PLAIN}). Applied last so they override any adapter
   * defaults.
   */
  public Properties extraProperties() {
    var props = new Properties();
    if (propertiesRaw == null || propertiesRaw.isBlank()) {
      return props;
    }
    for (String entry : propertiesRaw.split(";")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        throw new IllegalArgumentException(
            "Invalid freeway.kafka.properties entry (expected key=value): '" + trimmed + "'");
      }
      props.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    return props;
  }

  /** Returns true when poison messages should be forwarded to a DLQ topic. */
  public boolean dlqEnabled() {
    return dlqTopic != null && !dlqTopic.isBlank();
  }
}
