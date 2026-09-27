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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cell gates: total failure fails, climbing warns, settled passes quietly. */
class BenchRunnerTest {

  private static BenchRunner.IterationResult round(double rps) {
    return new BenchRunner.IterationResult(rps, 1, 2, 3, 0, false);
  }

  @Test
  void totalFailureNeedsEveryRoundAtZero() {
    assertTrue(BenchRunner.totalFailure(List.of(round(0), round(0))));
    assertFalse(BenchRunner.totalFailure(List.of(round(0), round(1))));
    assertFalse(BenchRunner.totalFailure(List.of()));
  }

  @Test
  void stillClimbingNeedsAQuarterSpreadOnTheTail() {
    assertTrue(BenchRunner.stillClimbing(List.of(round(100), round(200), round(300))));
    assertFalse(BenchRunner.stillClimbing(List.of(round(300), round(310), round(305))));
    assertFalse(BenchRunner.stillClimbing(List.of(round(100))));
  }

  @Test
  void stillClimbingIgnoresZeroRounds() {
    // A failed round is infrastructure, not a warmup ramp: steady rounds beside it must not warn.
    assertFalse(BenchRunner.stillClimbing(List.of(round(400), round(0), round(405), round(410))));
    // Two measurable rounds that really do spread still warn.
    assertTrue(BenchRunner.stillClimbing(List.of(round(0), round(100), round(300))));
    // Fewer than two measurable rounds cannot claim a ramp.
    assertFalse(BenchRunner.stillClimbing(List.of(round(0), round(0), round(500))));
  }
}
