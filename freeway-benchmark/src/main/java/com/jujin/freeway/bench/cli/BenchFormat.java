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

package com.jujin.freeway.bench.cli;

import java.util.List;
import java.util.Locale;

/**
 * Shared number and table formatting for CLI reports: one place decides how a rate reads ({@code
 * 1.20M}, never {@code 1200000}) and how every table is laid out, so the five commands print the
 * same shape.
 */
final class BenchFormat {

  private BenchFormat() {}

  /** Column alignment; numbers are right-aligned so their digits line up. */
  enum Align {
    LEFT,
    RIGHT
  }

  /** One table row. A bold row renders each cell as Markdown emphasis (used for medians). */
  record Row(List<String> cells, boolean bold) {

    static Row of(String... cells) {
      return new Row(List.of(cells), false);
    }

    Row asBold() {
      return new Row(cells, true);
    }
  }

  static String rps(double rps) {
    if (rps >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", rps / 1_000_000);
    if (rps >= 1_000) return String.format(Locale.ROOT, "%.1fk", rps / 1_000);
    return String.format(Locale.ROOT, "%.0f", rps);
  }

  static String delta(double d) {
    return String.format(Locale.ROOT, "%+.1f%%", d * 100);
  }

  static String micros(long us) {
    return us + "μs";
  }

  /**
   * Renders a Markdown table whose cells are padded to a common width, so it reads as a table both
   * in a terminal and in a Markdown viewer.
   *
   * @param headers column headers
   * @param aligns one alignment per column
   * @param rows row values, one entry per column, in header order
   */
  static String table(List<String> headers, List<Align> aligns, List<Row> rows) {
    int columns = headers.size();
    if (aligns.size() != columns) {
      throw new IllegalArgumentException(
          "aligns must match headers: " + aligns.size() + " != " + columns);
    }
    int[] widths = new int[columns];
    for (int i = 0; i < columns; i++) {
      widths[i] = headers.get(i).length();
    }
    for (Row row : rows) {
      if (row.cells().size() != columns) {
        throw new IllegalArgumentException(
            "row has " + row.cells().size() + " cells, expected " + columns + ": " + row.cells());
      }
      for (int i = 0; i < columns; i++) {
        widths[i] = Math.max(widths[i], row.cells().get(i).length());
      }
    }

    var out = new StringBuilder();
    appendRow(out, headers, aligns, widths, false);
    out.append('|');
    for (int i = 0; i < columns; i++) {
      out.append(aligns.get(i) == Align.RIGHT ? " --: |" : " --- |");
    }
    out.append('\n');
    for (Row row : rows) {
      appendRow(out, row.cells(), aligns, widths, row.bold());
    }
    return out.toString();
  }

  private static void appendRow(
      StringBuilder out, List<String> cells, List<Align> aligns, int[] widths, boolean bold) {
    out.append('|');
    for (int i = 0; i < cells.size(); i++) {
      String cell = cells.get(i);
      String pad = " ".repeat(widths[i] - cell.length());
      // Pad outside the emphasis markers, so a bold numeric cell still lines up
      // with the plain rows above it.
      out.append(' ');
      if (bold && aligns.get(i) == Align.RIGHT)
        out.append(pad).append("**").append(cell).append("**");
      else if (bold) out.append("**").append(cell).append("**").append(pad);
      else out.append(aligns.get(i) == Align.RIGHT ? pad + cell : cell + pad);
      out.append(" |");
    }
    out.append('\n');
  }

  /**
   * Validates that an {@code --output} path carries the extension of the report the command writes,
   * so the flag cannot silently produce a JSON file named {@code report.md}.
   */
  static void requireOutputExtension(String path, String extension, String formatName) {
    if (!path.toLowerCase(Locale.ROOT).endsWith(extension)) {
      throw new UsageException(
          "--output must end in "
              + extension
              + " (this command writes a "
              + formatName
              + " report), got: "
              + path);
    }
  }
}
