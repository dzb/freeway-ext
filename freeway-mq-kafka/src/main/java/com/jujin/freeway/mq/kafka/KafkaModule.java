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

import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/** IoC module wiring the Kafka event sink and subscriber into the container. */
public class KafkaModule implements ModuleEx {

  private static final SymbolSpec<Integer> MAX_RETRIES =
      SymbolSpec.of("freeway.kafka.max-retries", Integer.class, 1, Integer::parseInt);
  private static final SymbolSpec<Long> RETRY_BACKOFF_MS =
      SymbolSpec.of("freeway.kafka.retry-backoff-ms", Long.class, 1000L, Long::parseLong);
  private static final SymbolSpec<Integer> CONCURRENCY =
      SymbolSpec.of("freeway.kafka.concurrency", Integer.class, 1, Integer::parseInt);

  @Override
  public void bind(Binder binder) {
    binder
        .bind(KafkaConfig.class)
        .to(
            container -> {
              SymbolSource symbols = container.get(SymbolSource.class);
              return KafkaConfig.of(
                  symbols.resolve("freeway.kafka.bootstrap-servers", "localhost:9092"),
                  symbols.resolve("freeway.kafka.group-id", "freeway"),
                  symbols.resolve("freeway.kafka.client-id", ""),
                  symbols.resolve("freeway.kafka.topics", ""),
                  symbols.resolve("freeway.kafka.allowed-event-types", ""),
                  symbols.resolve("freeway.kafka.poison-policy", "skip"),
                  symbols.resolve("freeway.kafka.properties", ""),
                  symbols.resolve("freeway.kafka.dlq-topic", ""),
                  MAX_RETRIES.parse(symbols.resolve(MAX_RETRIES.key(), null)),
                  RETRY_BACKOFF_MS.parse(symbols.resolve(RETRY_BACKOFF_MS.key(), null)),
                  CONCURRENCY.parse(symbols.resolve(CONCURRENCY.key(), null)),
                  Boolean.parseBoolean(symbols.resolve("freeway.kafka.suppress-own", "true")));
            });
    // Provider lambdas: constructor injection would select the max-param
    // constructor, which for these classes is the package-private test seam
    // (KafkaEventSink(config, codec, Producer)) — never reachable in
    // production. Bind explicitly instead.
    binder
        .bind(KafkaEventSink.class)
        .to(c -> new KafkaEventSink(c.get(KafkaConfig.class), c.get(JsonCodec.class)));
    binder
        .bind(KafkaSubscriber.class)
        .to(
            c ->
                new KafkaSubscriber(
                    c.get(KafkaConfig.class), c.get(EventBus.class), c.get(JsonCodec.class)));

    binder
        .contribute(RuntimeHook.class)
        .add(
            "kafka-sink",
            new RuntimeHook() {
              @Override
              public void start(Container container) {
                container.get(EventBus.class).addEventSink(container.get(KafkaEventSink.class));
                container.get(KafkaSubscriber.class).start();
              }

              @Override
              public void stop(Container container) {
                // Detach before closing: a publish during shutdown must not
                // reach a closed producer.
                KafkaEventSink sink = container.get(KafkaEventSink.class);
                container.get(EventBus.class).removeEventSink(sink);
                container.get(KafkaSubscriber.class).close();
                sink.close();
              }
            });
  }
}
