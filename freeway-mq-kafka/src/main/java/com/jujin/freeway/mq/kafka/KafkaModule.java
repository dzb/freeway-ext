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

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/** IoC module wiring the Kafka event bridge and subscriber into the container. */
public class KafkaModule implements ModuleEx {

  @Override
  public void bind(Binder binder) {
    binder
        .bind(KafkaConfig.class)
        .to(
            container ->
                KafkaConfig.of(
                    resolve(container, "freeway.kafka.bootstrap-servers", "localhost:9092"),
                    resolve(container, "freeway.kafka.group-id", "freeway"),
                    resolve(container, "freeway.kafka.client-id", ""),
                    resolve(container, "freeway.kafka.topics", ""),
                    resolve(container, "freeway.kafka.allowed-event-types", ""),
                    resolve(container, "freeway.kafka.poison-policy", "skip"),
                    resolve(container, "freeway.kafka.properties", ""),
                    resolve(container, "freeway.kafka.dlq-topic", ""),
                    Integer.parseInt(resolve(container, "freeway.kafka.max-retries", "1")),
                    Long.parseLong(resolve(container, "freeway.kafka.retry-backoff-ms", "1000")),
                    Integer.parseInt(resolve(container, "freeway.kafka.concurrency", "1")),
                    Boolean.parseBoolean(
                        resolve(container, "freeway.kafka.suppress-own", "true"))));
    // Provider lambdas: constructor injection would select the max-param
    // constructor, which for these classes is the package-private test seam
    // (KafkaEventBridge(config, codec, Producer)) — never reachable in
    // production. Bind explicitly instead.
    binder
        .bind(KafkaEventBridge.class)
        .to(c -> new KafkaEventBridge(c.get(KafkaConfig.class), c.get(JsonCodec.class)));
    binder
        .bind(KafkaSubscriber.class)
        .to(
            c ->
                new KafkaSubscriber(
                    c.get(KafkaConfig.class), c.get(EventBus.class), c.get(JsonCodec.class)));

    binder
        .contribute(RuntimeHook.class)
        .add(
            "kafka-bridge",
            new RuntimeHook() {
              @Override
              public void start(Container container) {
                container.get(EventBus.class).setEventBridge(container.get(KafkaEventBridge.class));
                container.get(KafkaSubscriber.class).start();
              }

              @Override
              public void stop(Container container) {
                container.get(KafkaSubscriber.class).close();
                container.get(KafkaEventBridge.class).close();
              }
            });
  }

  private static String resolve(Container container, String key, String defaultValue) {
    return container.get(SymbolSource.class).resolve(key, defaultValue);
  }
}
