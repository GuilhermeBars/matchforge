package io.github.guilhermebars.matchforge.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class FixedPointTest {
    @Test void parsesAndFormatsExactDecimals() {
        assertThat(Price.parse("123.4500", 2)).isEqualTo(new Price(12345, 2));
        assertThat(Price.parse("123.4", 2).toString()).isEqualTo("123.40");
        assertThat(Quantity.parse("0.00000001", 8)).isEqualTo(new Quantity(1, 8));
        assertThat(new Quantity(1, 18).toString()).isEqualTo("0.000000000000000001");
        assertThat(Price.parse("9223372036854775807", 0).units()).isEqualTo(Long.MAX_VALUE);
        assertThat(Quantity.parse("0", 8).toString()).isEqualTo("0.00000000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 1", "1 ", "-1", "+1", "1e2", "NaN", "1,2", ".5", "1."})
    void rejectsNonPlainUnsignedDecimals(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> Price.parse(value, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> Quantity.parse(value, 8));
    }

    @Test void rejectsPrecisionLossAndOverflow() {
        assertThatThrownBy(() -> Price.parse("0.001", 2)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Quantity.parse("0.000000001", 8)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Price.parse("9223372036854775808", 0)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Quantity.parse("9223372036854775808", 0)).isInstanceOf(ArithmeticException.class);
    }

    @Test void rejectsNegativeUnitsInvalidScalesAndNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Price(-1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Quantity(-1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Price(1, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Quantity(1, 19));
        assertThatNullPointerException().isThrownBy(() -> Price.parse(null, 2));
    }

    @Test void multipliesExactlyInExplicitQuoteScale() {
        assertThat(Price.parse("123.45", 2).multiply(Quantity.parse("0.5", 8), 10))
                .isEqualTo(617250000000L);
        assertThat(Price.parse("100", 2).multiply(Quantity.parse("0.5", 8), 2)).isEqualTo(5000);
        assertThat(new Price(0, 18).multiply(new Quantity(1, 18), 0)).isZero();
        assertThatThrownBy(() -> new Price(Long.MAX_VALUE, 0).multiply(new Quantity(2, 0), 0))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Price.parse("0.01", 2).multiply(Quantity.parse("0.01", 2), 2))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Price(Long.MAX_VALUE, 0).multiply(new Quantity(1, 0), 1))
                .isInstanceOf(ArithmeticException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new Price(1, 0).multiply(new Quantity(1, 0), 19));
    }

    @Test void quantityArithmeticPreservesScaleAndChecksBounds() {
        assertThat(new Quantity(7, 2).add(new Quantity(3, 2))).isEqualTo(new Quantity(10, 2));
        assertThat(new Quantity(7, 2).subtract(new Quantity(7, 2))).isEqualTo(new Quantity(0, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Quantity(1, 2).subtract(new Quantity(2, 2)));
        assertThatThrownBy(() -> new Quantity(Long.MAX_VALUE, 2).add(new Quantity(1, 2)))
                .isInstanceOf(ArithmeticException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new Quantity(1, 2).add(new Quantity(1, 3)));
    }

    @Test void comparesOnlyCanonicalScales() {
        assertThat(new Price(1, 2)).isLessThan(new Price(2, 2));
        assertThat(new Quantity(2, 8)).isGreaterThan(new Quantity(1, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new Price(1, 2).compareTo(new Price(1, 3)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Quantity(1, 2).compareTo(new Quantity(1, 3)));
    }
}
