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

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.RuntimeHook;
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
    System.clearProperty("freeway.kafka.max-retries");
    System.clearProperty("freeway.kafka.retry-backoff-ms");
    System.clearProperty("freeway.kafka.concurrency");
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
      assertTrue(config.suppressOwn(), "suppress-own must default to true");
      assertEquals("SASL_SSL", config.extraProperties().getProperty("security.protocol"));
    }
  }

  @Test
  void malformedNumericConfigFailsFastNamingTheKey() {
    // A bad number must not surface as a bare NumberFormatException — the
    // failing key and the rejected raw value belong in the message.
    System.setProperty("freeway.kafka.max-retries", "soon");
    try {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> {
                try (Container container = Freeway.create(new KafkaModule())) {
                  container.get(KafkaConfig.class);
                }
              });
      assertTrue(
          ex.getMessage().contains("freeway.kafka.max-retries"),
          "the failing key must be named: " + ex.getMessage());
      assertTrue(
          ex.getMessage().contains("soon"),
          "the rejected value must be quoted: " + ex.getMessage());
    } finally {
      System.clearProperty("freeway.kafka.max-retries");
    }
  }

  @Test
  void hookStopDetachesTheSinkFromTheBus() throws Exception {
    // No topics: KafkaSubscriber.start() is a no-op, so this exercises the
    // hook without a broker.
    System.setProperty("freeway.kafka.bootstrap-servers", "127.0.0.1:1");
    System.setProperty("freeway.kafka.group-id", "container-test");

    // JsonCodec is a builtin of the app runtime, not of a bare container.
    try (Container container =
        Freeway.create(
            new KafkaModule(), binder -> binder.bind(JsonCodec.class).to(new JsonCodecDefault()))) {
      EventBus bus = container.get(EventBus.class);
      KafkaEventSink sink = container.get(KafkaEventSink.class);
      RuntimeHook hook = container.extension(RuntimeHook.class).all().get(0);

      hook.start(container);
      assertTrue(bus.removeEventSink(sink), "hook start installs the sink");
      bus.addEventSink(sink);

      hook.stop(container);

      assertFalse(
          bus.removeEventSink(sink),
          "hook stop must detach the sink — a publish during shutdown must "
              + "not reach a closed producer");
    }
  }
}
