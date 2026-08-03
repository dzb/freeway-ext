package com.jujin.freeway.bench.cli;

import java.util.Locale;

/** Shared number formatting for CLI reports. */
final class BenchFormat {

    private BenchFormat() {}

    static String rps(double rps) {
        if (rps >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", rps / 1_000_000);
        if (rps >= 1_000) return String.format(Locale.ROOT, "%.1fk", rps / 1_000);
        return String.format(Locale.ROOT, "%.0f", rps);
    }

    static String delta(double d) {
        return String.format(Locale.ROOT, "%+.1f%%", d * 100);
    }
}
