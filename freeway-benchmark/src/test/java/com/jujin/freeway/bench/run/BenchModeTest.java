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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.bench.harness.ServerHarness;
import org.junit.jupiter.api.Test;

/** One mapping from the {@code --mode} spelling to the client mode, scenario and label. */
class BenchModeTest {

  @Test
  void keepaliveIsTheDefaultAndAcceptsItsLongAlias() {
    assertEquals("keepalive", BenchMode.of(null).label());
    assertEquals("keepalive", BenchMode.of("").label());
    assertEquals("keepalive", BenchMode.of("keepalive").label());
    assertEquals(BenchRunner.Mode.KEEPALIVE, BenchMode.of("long").clientMode());
    assertEquals(BenchRunner.Mode.KEEPALIVE, BenchMode.of("KEEPALIVE").clientMode());
    assertEquals(BenchRunner.Mode.KEEPALIVE, BenchMode.DEFAULT.clientMode());
  }

  @Test
  void shortModeStaysOnThePingScenario() {
    var mode = BenchMode.of("SHORT");

    assertEquals(BenchRunner.Mode.SHORT, mode.clientMode());
    assertEquals("short", mode.label());
    assertFalse(mode.webSocket());
    assertEquals(ServerHarness.Scenario.PING, mode.scenario());
  }

  @Test
  void webSocketSpellingsSelectTheEchoScenario() {
    for (String spelling : new String[] {"ws", "WS", "websocket"}) {
      var mode = BenchMode.of(spelling);
      assertEquals(BenchRunner.Mode.WS, mode.clientMode(), spelling);
      assertEquals("ws", mode.label(), spelling);
      assertTrue(mode.webSocket(), spelling);
      assertEquals(ServerHarness.Scenario.WS_ECHO, mode.scenario(), spelling);
    }
  }

  @Test
  void unknownSpellingIsRejectedInsteadOfSilentlyMeasuredAsKeepalive() {
    // The CLI turns this into a usage error naming --mode; a forked run fails fast.
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> BenchMode.of("wl"));
    assertTrue(failure.getMessage().contains("wl"), failure.getMessage());
    assertTrue(failure.getMessage().contains("keepalive"), failure.getMessage());
  }
}
