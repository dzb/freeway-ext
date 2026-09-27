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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The child-process handshake: what counts as "the server is ready". */
class BenchProcessesTest {

  @Test
  void clientCpuReadingIsAValidLoad() {
    double load = BenchRunner.processCpu();
    assertTrue(load >= -1 && load <= 1, "withheld (-1) or a 0..1 load, never anything else");
  }

  @Test
  void pinningIsIdentityWithoutARange() {
    var command = List.of("java", "-cp", "x");
    assertEquals(command, BenchProcesses.pinned(command, ""));
    assertEquals(command, BenchProcesses.pinned(command, null));
    assertEquals(
        List.of("taskset", "-c", "0-7", "java", "-cp", "x"), BenchProcesses.pinned(command, "0-7"));
  }

  @Test
  void readsTheAnnouncedPort() {
    assertEquals(
        43201,
        BenchProcesses.parseReadyPort(
            "log line\nREADY port=43201 engine=freeway scenario=PING\nmore\n"));
  }

  @Test
  void anyPositionOnTheReadyLineWorks() {
    assertEquals(1234, BenchProcesses.parseReadyPort("READY engine=freeway port=1234"));
  }

  @Test
  void absentOrMalformedHandshakeIsNotReady() {
    assertEquals(-1, BenchProcesses.parseReadyPort(null));
    assertEquals(-1, BenchProcesses.parseReadyPort("starting up\n"));
    assertEquals(-1, BenchProcesses.parseReadyPort("READY engine=freeway\n"));
    assertEquals(-1, BenchProcesses.parseReadyPort("READY port=soon\n"));
  }
}
