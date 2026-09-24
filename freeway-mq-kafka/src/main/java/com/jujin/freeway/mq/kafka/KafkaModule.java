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
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/**
 * IoC module wiring the Kafka event sink and subscriber into the container.
 *
 * <p>Its bindings carry no {@code .id(...)}/{@code .primary()}: {@code KafkaEventSink} and {@code
 * KafkaSubscriber} have no framework-provided default to step aside for, so a plain binding is the
 * honest one — an application that binds its own sink gets the duplicate-binding error instead of
 * being silently outranked. Adapters that substitute a core role do the opposite: {@code
 * HikariPool} binds {@code Pool} with {@code .id("hikari").primary()} so the built-in default steps
 * aside.
 *
 * <p>Lifecycle (attach the sink to the {@code EventBus}, start and close the subscriber) is
 * contributed as the {@value #LIFECYCLE_HOOK} runtime hook.
 */
public final class KafkaModule implements ModuleEx {

  /** Runtime-hook id for the Kafka sink/subscriber lifecycle. */
  public static final String LIFECYCLE_HOOK = "freeway.kafka.lifecycle";

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

    // The sink is a sealed contribution, not a runtime install: it needs only
    // config and codec, both available at composition time. Resolved through
    // the binding (not built twice) so exactly one producer exists — the one
    // the stop hook closes.
    binder
        .contribute(EventSink.class)
        .add("freeway.kafka", c -> c.get(KafkaEventSink.class));

    binder
        .contribute(RuntimeHook.class)
        .add(
            LIFECYCLE_HOOK,
            new RuntimeHook() {
              @Override
              public void start(Container container) {
                container.get(KafkaSubscriber.class).start();
              }

              @Override
              public void stop(Container container) {
                // The contributed sink needs no detach: post-close publishes
                // are rejected, so they never reach the closed producer.
                container.get(KafkaSubscriber.class).close();
                container.get(KafkaEventSink.class).close();
              }
            });
  }
}
