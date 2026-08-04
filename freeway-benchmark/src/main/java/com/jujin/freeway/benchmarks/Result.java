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

package com.jujin.freeway.benchmarks;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * One benchmark iteration result, serialized as {@code key=value} pairs for exchange between the
 * forked server/client processes.
 */
public record Result(
    String engine,
    String mode,
    int requests,
    int ok,
    int errors,
    double rps,
    long p50us,
    long p95us,
    long p99us) {
  @Override
  public String toString() {
    return String.format(
        Locale.ROOT,
        "engine=%s mode=%s requests=%d ok=%d errors=%d rps=%.0f p50=%d p95=%d p99=%d",
        engine,
        mode,
        requests,
        ok,
        errors,
        rps,
        p50us,
        p95us,
        p99us);
  }

  /** Median result across iterations (median of each field). */
  public static Result median(List<Result> rs) {
    var sorted = rs.stream().sorted(Comparator.comparingDouble(Result::rps)).toList();
    Result mid = sorted.get(sorted.size() / 2);
    return new Result(
        mid.engine,
        mid.mode,
        mInt(rs, Result::requests),
        mInt(rs, Result::ok),
        mInt(rs, Result::errors),
        mDbl(rs, Result::rps),
        mLong(rs, Result::p50us),
        mLong(rs, Result::p95us),
        mLong(rs, Result::p99us));
  }

  private static int mInt(List<Result> rs, ToIntFunction<Result> g) {
    return rs.stream().mapToInt(g).sorted().skip(rs.size() / 2).findFirst().orElse(0);
  }

  private static long mLong(List<Result> rs, ToLongFunction<Result> g) {
    return rs.stream().mapToLong(g).sorted().skip(rs.size() / 2).findFirst().orElse(0);
  }

  private static double mDbl(List<Result> rs, ToDoubleFunction<Result> g) {
    return rs.stream().mapToDouble(g).sorted().skip(rs.size() / 2).findFirst().orElse(0);
  }

  /** Parses a {@code RESULT ...} line emitted by a forked client. */
  public static Result fromLine(String line) {
    String[] parts = line.substring("RESULT ".length()).split(" ");
    return new Result(
        val(parts, "engine"),
        val(parts, "mode"),
        Integer.parseInt(val(parts, "requests")),
        Integer.parseInt(val(parts, "ok")),
        Integer.parseInt(val(parts, "errors")),
        Double.parseDouble(val(parts, "rps")),
        Long.parseLong(val(parts, "p50")),
        Long.parseLong(val(parts, "p95")),
        Long.parseLong(val(parts, "p99")));
  }

  private static String val(String[] parts, String key) {
    for (String p : parts) if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
    throw new IllegalArgumentException("Missing " + key);
  }
}
