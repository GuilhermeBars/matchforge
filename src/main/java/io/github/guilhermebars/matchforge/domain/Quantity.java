package io.github.guilhermebars.matchforge.domain;

/** Non-negative base quantity; zero is useful for exhausted order remainders. */
public record Quantity(long units, int scale) implements Comparable<Quantity> {
    public Quantity {
        FixedPoint.validate(units, scale);
    }

    public static Quantity parse(String decimal, int scale) {
        return new Quantity(FixedPoint.parse(decimal, scale), scale);
    }

    public Quantity add(Quantity other) {
        FixedPoint.requireSameScale(scale, other.scale);
        return new Quantity(Math.addExact(units, other.units), scale);
    }

    public Quantity subtract(Quantity other) {
        FixedPoint.requireSameScale(scale, other.scale);
        return new Quantity(Math.subtractExact(units, other.units), scale);
    }

    @Override
    public int compareTo(Quantity other) {
        FixedPoint.requireSameScale(scale, other.scale);
        return Long.compare(units, other.units);
    }

    @Override
    public String toString() {
        return FixedPoint.format(units, scale);
    }
}
