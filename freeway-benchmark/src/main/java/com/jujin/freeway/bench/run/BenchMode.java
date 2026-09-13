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

package com.jujin.freeway.bench.run;

import com.jujin.freeway.bench.harness.ServerHarness;
import java.util.Locale;

/**
 * The {@code --mode} flag in one place: its accepted spellings, the client mode it selects, the
 * scenario a forked run uses for it, and the label persisted with a result. The CLI commands and
 * {@link BenchFork} all read it from here, so a mode cannot mean one thing in one entry point and
 * something else in another.
 *
 * <p>An unknown spelling is rejected instead of silently falling back to {@code keepalive}: the CLI
 * turns that into a usage error naming {@code --mode} (see {@code Command.Context#parse}).
 */
public final class BenchMode {

  /** The default {@code --mode}. */
  public static final BenchMode DEFAULT = new BenchMode(BenchRunner.Mode.KEEPALIVE, "keepalive");

  private final BenchRunner.Mode clientMode;
  private final String label;

  private BenchMode(BenchRunner.Mode clientMode, String label) {
    this.clientMode = clientMode;
    this.label = label;
  }

  /**
   * Parses a mode spelling: {@code keepalive} (alias {@code long}), {@code short} or {@code ws}.
   */
  public static BenchMode of(String mode) {
    String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "", "keepalive", "long" -> DEFAULT;
      case "short" -> new BenchMode(BenchRunner.Mode.SHORT, "short");
      case "ws", "websocket" -> new BenchMode(BenchRunner.Mode.WS, "ws");
      default ->
          throw new IllegalArgumentException(
              "unknown mode '" + mode + "'; expected keepalive, short or ws");
    };
  }

  /** The client-side measurement mode. */
  public BenchRunner.Mode clientMode() {
    return clientMode;
  }

  /** The canonical label persisted with results and printed in reports. */
  public String label() {
    return label;
  }

  public boolean webSocket() {
    return clientMode == BenchRunner.Mode.WS;
  }

  /**
   * The scenario a forked run uses for this mode. The CLI keeps {@code --scenario} as its own flag
   * (a mode may be combined with any scenario), so this convention belongs to the forked path.
   */
  public ServerHarness.Scenario scenario() {
    return webSocket() ? ServerHarness.Scenario.WS_ECHO : ServerHarness.Scenario.PING;
  }
}
