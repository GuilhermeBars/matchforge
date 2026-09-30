package io.github.guilhermebars.matchforge.domain;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

class FixedPointProperties {
    @Property(tries = 500)
    void decimalRoundTrip(@ForAll @LongRange(min = 0) long units,
                          @ForAll @IntRange(min = 0, max = 18) int scale) {
        var price = new Price(units, scale);
        var quantity = new Quantity(units, scale);
        assertThat(Price.parse(price.toString(), scale)).isEqualTo(price);
        assertThat(Quantity.parse(quantity.toString(), scale)).isEqualTo(quantity);
    }

    @Property(tries = 300)
    void exactMultiplication(@ForAll @LongRange(min = 0, max = 1_000_000) long price,
                            @ForAll @LongRange(min = 0, max = 1_000_000) long quantity) {
        assertThat(new Price(price, 2).multiply(new Quantity(quantity, 8), 10))
                .isEqualTo(price * quantity);
    }
}
