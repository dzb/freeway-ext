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

/** IoC module wiring the Kafka event sink and subscriber into the container. */
public class KafkaModule implements ModuleEx {

  @Override
  public void bind(Binder binder) {
    binder
        .bind(KafkaConfig.class)
        .to(container -> KafkaConfig.from(container.get(SymbolSource.class)));
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
