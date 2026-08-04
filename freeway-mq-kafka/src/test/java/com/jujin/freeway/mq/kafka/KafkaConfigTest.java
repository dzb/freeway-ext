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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KafkaConfigTest {

  private static KafkaConfig config(String topics, String allowed, String policy) {
    return new KafkaConfig(
        "localhost:9092", "test-group", "", topics, allowed, policy, "", "", 1, 1000, 1);
  }

  private static KafkaConfig config(String clientId, String topics, String allowed, String policy) {
    return new KafkaConfig(
        "localhost:9092", "test-group", clientId, topics, allowed, policy, "", "", 1, 1000, 1);
  }

  @Test
  void topicsParsesCommaSeparatedList() {
    assertEquals(List.of(), config("", "", "skip").topics());
    assertEquals(List.of(), config(null, "", "skip").topics());
    assertEquals(List.of("orders", "payments"), config(" orders , payments ", "", "skip").topics());
  }

  @Test
  void allowedEventTypesDefaultsToEmptyAllowlist() {
    assertTrue(config("orders", "", "skip").allowedEventTypes().isEmpty());
    assertTrue(config("orders", null, "skip").allowedEventTypes().isEmpty());
    assertEquals(
        Set.of("com.acme.OrderCreated", "com.acme.PaymentReceived"),
        config("orders", "com.acme.OrderCreated, com.acme.PaymentReceived", "skip")
            .allowedEventTypes());
  }

  @Test
  void poisonPolicyParsing() {
    assertFalse(config("orders", "", "skip").failOnPoison());
    assertFalse(config("orders", "", "SKIP").failOnPoison());
    assertFalse(config("orders", "", " skip ").failOnPoison());
    assertTrue(config("orders", "", "fail").failOnPoison());
    assertTrue(config("orders", "", "FAIL").failOnPoison());
  }

  @Test
  void unknownPoisonPolicyIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> config("orders", "", "retry"));
    assertThrows(IllegalArgumentException.class, () -> config("orders", "", ""));
  }

  @Test
  void clientIdDefaultsToEmpty() {
    assertEquals("", config("orders", "", "skip").clientId());
    assertEquals("bench-producer", config("bench-producer", "orders", "", "skip").clientId());
  }

  @Test
  void extraPropertiesParsesKeyValuePairs() {
    var config =
        new KafkaConfig(
            "localhost:9092",
            "test-group",
            "",
            "orders",
            "",
            "skip",
            "security.protocol=SASL_SSL; sasl.mechanism = PLAIN ;",
            "",
            1,
            1000,
            1);
    Properties props = config.extraProperties();
    assertEquals(2, props.size());
    assertEquals("SASL_SSL", props.getProperty("security.protocol"));
    assertEquals("PLAIN", props.getProperty("sasl.mechanism"));
  }

  @Test
  void extraPropertiesDefaultsToEmpty() {
    assertTrue(config("orders", "", "skip").extraProperties().isEmpty());
  }

  @Test
  void malformedExtraPropertyIsRejected() {
    var config =
        new KafkaConfig(
            "localhost:9092", "test-group", "", "orders", "", "skip", "just-a-key", "", 1, 1000, 1);
    assertThrows(IllegalArgumentException.class, config::extraProperties);
  }

  @Test
  void dlqDefaultsToDisabled() {
    assertFalse(config("orders", "", "skip").dlqEnabled());
    var enabled =
        new KafkaConfig(
            "localhost:9092", "test-group", "", "orders", "", "skip", "", "orders-dlq", 1, 1000, 1);
    assertTrue(enabled.dlqEnabled());
  }

  @Test
  void invalidConsumerSettingsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KafkaConfig(
                "localhost:9092", "test-group", "", "orders", "", "skip", "", "", -1, 1000, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KafkaConfig(
                "localhost:9092", "test-group", "", "orders", "", "skip", "", "", 1, -1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new KafkaConfig(
                "localhost:9092", "test-group", "", "orders", "", "skip", "", "", 1, 1000, 0));
  }
}
