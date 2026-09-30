package io.github.guilhermebars.matchforge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Exact boundary conversion only; engine arithmetic uses scaled longs. */
final class FixedPoint {
    private FixedPoint() {}

    static void validate(long units, int scale) {
        validateScale(scale);
        if (units < 0) throw new IllegalArgumentException("units must be non-negative");
    }

    static void validateScale(int scale) {
        if (scale < 0 || scale > 18) throw new IllegalArgumentException("scale must be between 0 and 18");
    }

    static long parse(String decimal, int scale) {
        validateScale(scale);
        Objects.requireNonNull(decimal, "decimal");
        if (!decimal.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("expected an unsigned plain decimal");
        }
        return new BigDecimal(decimal)
                .setScale(scale, RoundingMode.UNNECESSARY)
                .unscaledValue()
                .longValueExact();
    }

    static String format(long units, int scale) {
        return BigDecimal.valueOf(units, scale).toPlainString();
    }

    static void requireSameScale(int left, int right) {
        if (left != right) throw new IllegalArgumentException("scale mismatch");
    }
}
