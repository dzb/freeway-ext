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

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;

/** IoC module wiring the Kafka event bridge and subscriber into the container. */
public class KafkaModule implements ModuleEx {

  @Override
  public void bind(Binder binder) {
    binder.bind(KafkaConfig.class).to(KafkaConfig.class);
    binder.bind(KafkaEventBridge.class).to(KafkaEventBridge.class);
    binder.bind(KafkaSubscriber.class).to(KafkaSubscriber.class);

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
}
