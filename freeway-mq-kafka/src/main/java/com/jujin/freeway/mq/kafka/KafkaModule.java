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
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

/**
 * IoC module wiring the durable stream plane ({@link KafkaEvents}) into the
 * container.
 *
 * <p>Its bindings carry no {@code .id(...)}/{@code .primary()}: the plane has
 * no framework-provided default to step aside for, so a plain binding is the
 * honest one — an application that binds its own gets the duplicate-binding
 * error instead of being silently outranked. Adapters that substitute a core
 * role do the opposite: {@code HikariPool} binds {@code Pool} with
 * {@code .id("hikari").primary()} so the built-in default steps aside.</p>
 *
 * <p>Lifecycle (start polling, then close the poller and the producer) is
 * contributed as the {@value #LIFECYCLE_HOOK} runtime hook. Publishing and
 * subscribing are calls on the plane itself — nothing is installed onto any
 * other component at runtime.</p>
 */
public final class KafkaModule implements ModuleEx {

  /** Runtime-hook id for the Kafka plane lifecycle. */
  public static final String LIFECYCLE_HOOK = "freeway.kafka.lifecycle";

  @Override
  public void bind(Binder binder) {
    binder
        .bind(KafkaConfig.class)
        .to(container -> KafkaConfig.from(container.get(SymbolSource.class)));

    // One plane instance owns the producer, the subscription table and the
    // poller — bound through the container so the hook and every injectee
    // share it. The producer arrives at composition (broker connection is
    // lazy); a misconfigured bootstrap fails at first use, not at startup.
    binder
        .bind(KafkaEvents.class)
        .to(c -> new KafkaEvents(c.get(KafkaConfig.class), c.get(JsonCodec.class)));

    binder
        .contribute(RuntimeHook.class)
        .add(
            LIFECYCLE_HOOK,
            new RuntimeHook() {
              @Override
              public void start(Container container) {
                container.get(KafkaEvents.class).start();
              }

              @Override
              public void stop(Container container) {
                container.get(KafkaEvents.class).close();
              }
            });
  }
}
