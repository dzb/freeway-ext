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
    System.clearProperty("freeway.kafka.client-id");
    System.clearProperty("freeway.kafka.topics");
    System.clearProperty("freeway.kafka.allowed-event-types");
    System.clearProperty("freeway.kafka.poison-policy");
    System.clearProperty("freeway.kafka.properties");
  }

  @Test
  void injectsKafkaConfigFromSystemProperties() {
    System.setProperty("freeway.kafka.bootstrap-servers", "kafka-test:9092");
    System.setProperty("freeway.kafka.group-id", "container-test");
    System.setProperty("freeway.kafka.client-id", "container-client");
    System.setProperty("freeway.kafka.topics", "orders, payments");
    System.setProperty("freeway.kafka.allowed-event-types", "com.acme.OrderCreated");
    System.setProperty("freeway.kafka.poison-policy", "fail");
    System.setProperty(
        "freeway.kafka.properties", "security.protocol=SASL_SSL;sasl.mechanism=PLAIN");

    try (Container container = Freeway.create(new KafkaModule())) {
      KafkaConfig config = container.get(KafkaConfig.class);
      assertEquals("kafka-test:9092", config.bootstrapServers());
      assertEquals("container-test", config.groupId());
      assertEquals("container-client", config.clientId());
      assertEquals(List.of("orders", "payments"), config.topics());
      assertEquals(Set.of("com.acme.OrderCreated"), config.allowedEventTypes());
      assertTrue(config.failOnPoison());
      assertEquals("SASL_SSL", config.extraProperties().getProperty("security.protocol"));
    }
  }
}
