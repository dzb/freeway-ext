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
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Configuration for the Kafka adapter, resolved from the config cascade via {@code @Value} (e.g.
 * {@code freeway.kafka.bootstrap-servers}). Components are fully parsed — raw config-cascade
 * strings are normalized by {@link #of}.
 */
public record KafkaConfig(
    String bootstrapServers,
    String groupId,
    String clientId,
    List<String> topics,
    Set<String> allowedEventTypes,
    PoisonPolicy poisonPolicy,
    Properties extraProperties,
    String dlqTopic,
    int maxRetries,
    long retryBackoffMs,
    int concurrency,
    boolean suppressOwn) {

  /** Poison-message policy: {@code SKIP} logs and continues, {@code FAIL} stops the subscriber. */
  public enum PoisonPolicy {
    SKIP,
    FAIL
  }

  private static final String PROCESS_ORIGIN = UUID.randomUUID().toString();

  /**
   * Adapting factory: parses the raw config-cascade strings (see the {@code freeway.kafka.*}
   * defaults) into the typed components above, validating as it goes.
   */
  public static KafkaConfig of(
      @Value("${freeway.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
      @Value("${freeway.kafka.group-id:freeway}") String groupId,
      @Value("${freeway.kafka.client-id:}") String clientId,
      @Value("${freeway.kafka.topics:}") String topicsRaw,
      @Value("${freeway.kafka.allowed-event-types:}") String allowedEventTypesRaw,
      @Value("${freeway.kafka.poison-policy:skip}") String poisonPolicyRaw,
      @Value("${freeway.kafka.properties:}") String propertiesRaw,
      @Value("${freeway.kafka.dlq-topic:}") String dlqTopic,
      @Value("${freeway.kafka.max-retries:1}") int maxRetries,
      @Value("${freeway.kafka.retry-backoff-ms:1000}") long retryBackoffMs,
      @Value("${freeway.kafka.concurrency:1}") int concurrency,
      @Value("${freeway.kafka.suppress-own:true}") boolean suppressOwn) {
    if (!isValidPoisonPolicy(poisonPolicyRaw)) {
      throw new IllegalArgumentException(
          "freeway.kafka.poison-policy must be 'skip' or 'fail', got: '" + poisonPolicyRaw + "'");
    }
    var poisonPolicy = PoisonPolicy.valueOf(poisonPolicyRaw.trim().toUpperCase(Locale.ROOT));
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
    return new KafkaConfig(
        bootstrapServers,
        groupId,
        clientId,
        parseList(topicsRaw),
        parseSet(allowedEventTypesRaw),
        poisonPolicy,
        parseProperties(propertiesRaw),
        dlqTopic,
        maxRetries,
        retryBackoffMs,
        concurrency,
        suppressOwn);
  }

  /**
   * Per-process identity used to recognize this node's own messages. Falls back to a UUID unique
   * per JVM when no {@code clientId} is configured, so the bridge and the subscriber always agree
   * on the origin even across separate {@link KafkaConfig} instances. Set a unique {@code
   * freeway.kafka.client-id} per node for a stable identity across restarts.
   */
  public String origin() {
    if (clientId != null && !clientId.isBlank()) {
      return clientId;
    }
    return PROCESS_ORIGIN;
  }

  /**
   * Poison-message policy: {@code skip} (default) logs an error and continues, {@code fail} stops
   * the subscriber so the offset is not committed.
   */
  public boolean failOnPoison() {
    return poisonPolicy == PoisonPolicy.FAIL;
  }

  /** Returns true when poison messages should be forwarded to a DLQ topic. */
  public boolean dlqEnabled() {
    return dlqTopic != null && !dlqTopic.isBlank();
  }

  private static List<String> parseList(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static Set<String> parseSet(String raw) {
    if (raw == null || raw.isBlank()) return Set.of();
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Parses extra Kafka client properties in {@code key=value} pairs separated by semicolons (e.g.
   * {@code security.protocol=SASL_SSL;sasl.mechanism=PLAIN}).
   */
  private static Properties parseProperties(String raw) {
    var props = new Properties();
    if (raw == null || raw.isBlank()) {
      return props;
    }
    for (String entry : raw.split(";")) {
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

  private static boolean isValidPoisonPolicy(String policy) {
    if (policy == null || policy.isBlank()) {
      return false;
    }
    String trimmed = policy.trim();
    return "skip".equalsIgnoreCase(trimmed) || "fail".equalsIgnoreCase(trimmed);
  }
}
