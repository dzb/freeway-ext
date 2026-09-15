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

package com.jujin.freeway.bench.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.cli.CliModule;
import com.jujin.freeway.bench.db.BenchDbModule;
import com.jujin.freeway.bench.model.BenchmarkResult;
import com.jujin.freeway.bench.model.BenchmarkRun;
import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.EventSubscriber;
import com.jujin.freeway.ioc.ModuleNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The bench events are dispatchable through the CLI's own EventBus: a subscriber contributed by the
 * modules receives every subtype (hierarchy dispatch), which is what makes the publish calls in the
 * commands a real EventBus flow rather than a publish-only path.
 */
class BenchEventListenerTest {

  private static final List<BenchEvent> RECEIVED = new ArrayList<>();

  /** A subscriber module alongside the CLI's own, proving the events are dispatchable. */
  public static final class BenchEventsModule implements com.jujin.freeway.ioc.ModuleEx {
    @Override
    public void bind(com.jujin.freeway.ioc.Binder binder) {
      binder
          .contribute(EventSubscriber.class)
          .add(EventSubscriber.of(BenchEvent.class, RECEIVED::add));
    }
  }

  @AfterEach
  void clearProperties() {
    for (String key :
        List.of(
            "freeway.db.url",
            "freeway.db.username",
            "freeway.db.password",
            "freeway.db.pool.max-size",
            "freeway.db.pool.min-idle")) {
      System.clearProperty(key);
    }
  }

  @Test
  void everyBenchEventReachesASubscriber() {
    System.setProperty("freeway.db.url", "jdbc:sqlite::memory:");
    System.setProperty("freeway.db.username", "sa");
    System.setProperty("freeway.db.password", "");
    System.setProperty("freeway.db.pool.max-size", "1");
    System.setProperty("freeway.db.pool.min-idle", "0");

    RECEIVED.clear();
    try (AppRuntime app =
        FreewayApp.create(
                ModuleNode.app(
                    "bench-events",
                    BenchEventsModule.class,
                    BenchDbModule.class,
                    DbModule.class,
                    CliModule.class))
            .autoDiscovery(false)
            .shutdownHook(false)
            .start()) {
      EventBus bus = app.get(EventBus.class);
      var run = BenchmarkRun.create("freeway", "ping", 2, 100, 10, 1);
      var result =
          BenchmarkResult.forHttpIteration(1, "freeway/ping", "keepalive", 1000, 1, 2, 3, 0);

      bus.publish(new BenchEvent.RunStarted(run));
      bus.publish(new BenchEvent.ResultCollected(result));
      bus.publish(new BenchEvent.RunCompleted(7));

      assertEquals(3, RECEIVED.size(), "all three event types are delivered: " + RECEIVED);
      assertTrue(RECEIVED.get(0) instanceof BenchEvent.RunStarted);
      assertTrue(RECEIVED.get(1) instanceof BenchEvent.ResultCollected);
      assertTrue(RECEIVED.get(2) instanceof BenchEvent.RunCompleted);
    }
  }
}
