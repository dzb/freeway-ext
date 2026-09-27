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

package com.jujin.freeway.bench.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The fork wire format: saturated round-trips, and lines predating it read as clean. */
class ResultTest {

  private static Result result(boolean saturated) {
    return new Result("freeway", "keepalive", 100, 100, 0, 1000.0, 50, 100, 200, saturated);
  }

  private static Result withRps(double rps) {
    return new Result("freeway", "keepalive", 100, 100, 0, rps, 50, 100, 200, false);
  }

  @Test
  void medianWindowReadsTheTailOnly() {
    var rounds = List.of(withRps(1000), withRps(2000), withRps(3000), withRps(4000), withRps(5000));

    // Full window: the middle rps of all five; 0 and oversized windows are the same.
    assertEquals(3000.0, Result.median(rounds).rps());
    assertEquals(3000.0, Result.median(rounds, 0).rps());
    assertEquals(3000.0, Result.median(rounds, 9).rps());
    // Last two (4000/5000): the upper one, the same size/2 convention the suite's
    // representative-round pick uses — that is what makes the two printed medians agree.
    assertEquals(5000.0, Result.median(rounds, 2).rps());
  }

  @Test
  void roundTripKeepsSaturation() {
    assertEquals(result(true), Result.fromLine("RESULT " + result(true)));
    assertEquals(result(false), Result.fromLine("RESULT " + result(false)));
  }

  @Test
  void legacyLineWithoutSaturationReadsClean() {
    assertEquals(
        result(false),
        Result.fromLine(
            "RESULT engine=freeway mode=keepalive requests=100 ok=100 errors=0 "
                + "rps=1000 p50=50 p95=100 p99=200"));
  }

  @Test
  void medianFlagsSaturationWhenAnyRoundChoked() {
    var median = Result.median(List.of(result(false), result(true)));
    assertTrue(median.saturated());
    assertEquals(1000.0, median.rps());
  }
}
