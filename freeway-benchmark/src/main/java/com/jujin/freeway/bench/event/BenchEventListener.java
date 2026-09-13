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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The subscriber side of {@link BenchEvent}: the commands publish run/result/completion events, and
 * this one consumes them so the module's EventBus showcase has a consumer instead of a publish-only
 * path. It logs at DEBUG — the console already prints the same progress — which makes the flow
 * observable by raising the log level, and it is the hook an embedder replaces (or orders against)
 * to persist or stream results.
 */
public final class BenchEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(BenchEventListener.class);

  private BenchEventListener() {}

  /** Handles any {@link BenchEvent}; hierarchy dispatch delivers all three subtypes here. */
  public static void onEvent(BenchEvent event) {
    switch (event) {
      case BenchEvent.RunStarted started -> LOG.debug("bench run started: {}", started.run());
      case BenchEvent.ResultCollected collected ->
          LOG.debug("bench result collected: {}", collected.result().benchmark());
      case BenchEvent.RunCompleted completed ->
          LOG.debug("bench run completed: #{}", completed.runId());
    }
  }
}
