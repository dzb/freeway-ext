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

import com.jujin.freeway.db.schema.Column;
import com.jujin.freeway.db.schema.Generated;
import com.jujin.freeway.db.schema.Id;
import com.jujin.freeway.db.schema.Table;

/** An individual benchmark measurement within a run session. */
@Table("bench_results")
public record BenchmarkResult(
    @Id @Generated long id,
    @Column("run_id") long runId,
    @Column("benchmark") String benchmark,
    @Column("mode") String mode,
    @Column("score") double score,
    @Column("score_error") double scoreError,
    @Column("unit") String unit,
    @Column("p50_us") long p50us,
    @Column("p95_us") long p95us,
    @Column("p99_us") long p99us,
    @Column("errors") int errors) {
  public static BenchmarkResult of(
      long runId,
      String benchmark,
      String mode,
      double score,
      double scoreError,
      String unit,
      long p50us,
      long p95us,
      long p99us,
      int errors) {
    return new BenchmarkResult(
        0, runId, benchmark, mode, score, scoreError, unit, p50us, p95us, p99us, errors);
  }
}
