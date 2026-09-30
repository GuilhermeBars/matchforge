package io.github.guilhermebars.matchforge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Non-negative price in quote units per base unit. Scale is part of identity. */
public record Price(long units, int scale) implements Comparable<Price> {
    public Price { FixedPoint.validate(units, scale); }

    public static Price parse(String decimal, int scale) {
        return new Price(FixedPoint.parse(decimal, scale), scale);
    }

    /** Quote atoms at resultScale; rejects overflow and any fractional atom. */
    public long multiply(Quantity quantity, int resultScale) {
        Objects.requireNonNull(quantity, "quantity");
        FixedPoint.validateScale(resultScale);
        long product = Math.multiplyExact(units, quantity.units());
        return BigDecimal.valueOf(product, scale + quantity.scale())
                .setScale(resultScale, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    }

    @Override public int compareTo(Price other) {
        FixedPoint.requireSameScale(scale, other.scale);
        return Long.compare(units, other.units);
    }

    @Override public String toString() { return FixedPoint.format(units, scale); }
}
