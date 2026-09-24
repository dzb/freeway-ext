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

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Configuration for the Kafka adapter. Every key's name, type and default is declared exactly once
 * here ({@link #from}), and {@link #of} is the single validation path — the module binds this
 * record without restating a key, so a default cannot drift between two files.
 *
 * <p><b>The three keys that must agree across nodes:</b>
 *
 * <ul>
 *   <li>{@code freeway.kafka.topics} is the bridge topic list: the sink produces to it and the
 *       subscriber polls it. Both sides must configure the same list, or records are written to a
 *       topic nobody consumes (the local dispatch topic travels in the {@code ce-fwtopic} header
 *       instead, so one bridge topic serves every local topic).
 *   <li>{@code freeway.kafka.allowed-event-types} is the subscriber's accept list and must name
 *       every bridged event class: a record whose type header is not listed is rejected as poison,
 *       and string-topic events carry {@code java.lang.String} as their type, so bridging those
 *       needs that entry too. An empty list accepts nothing.
 *   <li>{@code freeway.kafka.client-id} is the node identity behind {@code suppress-own}: each node
 *       needs its own value (unset falls back to a per-JVM UUID). Two nodes sharing one id treat
 *       each other's events as their own and drop them.
 * </ul>
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

  // ── Key declarations: name, type and default stated exactly once ──
  private static final SymbolSpec<String> BOOTSTRAP_SERVERS =
      SymbolSpec.of("freeway.kafka.bootstrap-servers", String.class, "localhost:9092");
  private static final SymbolSpec<String> GROUP_ID =
      SymbolSpec.of("freeway.kafka.group-id", String.class, "freeway");
  private static final SymbolSpec<String> CLIENT_ID =
      SymbolSpec.of("freeway.kafka.client-id", String.class, "");
  private static final SymbolSpec<String> TOPICS =
      SymbolSpec.of("freeway.kafka.topics", String.class, "");
  private static final SymbolSpec<String> ALLOWED_EVENT_TYPES =
      SymbolSpec.of("freeway.kafka.allowed-event-types", String.class, "");
  private static final SymbolSpec<String> POISON_POLICY =
      SymbolSpec.of("freeway.kafka.poison-policy", String.class, "skip");
  private static final SymbolSpec<String> EXTRA_PROPERTIES =
      SymbolSpec.of("freeway.kafka.properties", String.class, "");
  private static final SymbolSpec<String> DLQ_TOPIC =
      SymbolSpec.of("freeway.kafka.dlq-topic", String.class, "");
  private static final SymbolSpec<Integer> MAX_RETRIES =
      SymbolSpec.of("freeway.kafka.max-retries", Integer.class, 1, Integer::parseInt);
  private static final SymbolSpec<Long> RETRY_BACKOFF_MS =
      SymbolSpec.of("freeway.kafka.retry-backoff-ms", Long.class, 1000L, Long::parseLong);
  private static final SymbolSpec<Integer> CONCURRENCY =
      SymbolSpec.of("freeway.kafka.concurrency", Integer.class, 1, Integer::parseInt);

  /**
   * Coercer-parsed on purpose: an unreadable value fails naming the key instead of silently
   * becoming {@code false}, which would silently disable own-event suppression.
   */
  private static final SymbolSpec<Boolean> SUPPRESS_OWN =
      SymbolSpec.of("freeway.kafka.suppress-own", Boolean.class, true);

  /**
   * Resolves the whole {@code freeway.kafka.*} surface from the config cascade. This is the only
   * place that knows the keys; {@link #of} stays the only place that validates them.
   */
  public static KafkaConfig from(SymbolSource symbols) {
    return of(
        symbols.resolve(BOOTSTRAP_SERVERS),
        symbols.resolve(GROUP_ID),
        symbols.resolve(CLIENT_ID),
        symbols.resolve(TOPICS),
        symbols.resolve(ALLOWED_EVENT_TYPES),
        symbols.resolve(POISON_POLICY),
        symbols.resolve(EXTRA_PROPERTIES),
        symbols.resolve(DLQ_TOPIC),
        symbols.resolve(MAX_RETRIES),
        symbols.resolve(RETRY_BACKOFF_MS),
        symbols.resolve(CONCURRENCY),
        symbols.resolve(SUPPRESS_OWN));
  }

  /**
   * Adapting factory: parses the raw config-cascade strings into the typed components above,
   * validating as it goes.
   */
  public static KafkaConfig of(
      String bootstrapServers,
      String groupId,
      String clientId,
      String topicsRaw,
      String allowedEventTypesRaw,
      String poisonPolicyRaw,
      String propertiesRaw,
      String dlqTopic,
      int maxRetries,
      long retryBackoffMs,
      int concurrency,
      boolean suppressOwn) {
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
   * per JVM when no {@code clientId} is configured, so the sink and the subscriber always agree on
   * the origin even across separate {@link KafkaConfig} instances. Set a unique {@code
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
