package io.github.guilhermebars.matchforge.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class InstrumentConfigTest {
    private InstrumentConfig instrument(Price tick, Quantity lot) {
        return new InstrumentConfig(new Symbol("BTC-USD"), new Asset("BTC"), new Asset("USD"), tick, lot, 2, 8);
    }

    @Test
    void validatesTickAndLotMultiples() {
        var market = instrument(Price.parse("0.05", 2), Quantity.parse("0.00000100", 8));
        assertThatCode(() -> market.validatePrice(Price.parse("123.45", 2))).doesNotThrowAnyException();
        assertThatCode(() -> market.validateQuantity(Quantity.parse("0.00000300", 8)))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> market.validatePrice(Price.parse("123.46", 2)));
        assertThatIllegalArgumentException().isThrownBy(() -> market.validateQuantity(new Quantity(101, 8)));
        assertThatIllegalArgumentException().isThrownBy(() -> market.validatePrice(new Price(0, 2)));
        assertThatIllegalArgumentException().isThrownBy(() -> market.validateQuantity(new Quantity(0, 8)));
        assertThatIllegalArgumentException().isThrownBy(() -> market.validatePrice(new Price(500, 3)));
        assertThatIllegalArgumentException().isThrownBy(() -> market.validateQuantity(new Quantity(100, 7)));
        assertThatNullPointerException().isThrownBy(() -> market.validatePrice(null));
        assertThatNullPointerException().isThrownBy(() -> market.validateQuantity(null));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> instrument(new Price(0, 2), new Quantity(1, 8)));
        assertThatIllegalArgumentException().isThrownBy(() -> instrument(new Price(1, 2), new Quantity(0, 8)));
        assertThatIllegalArgumentException().isThrownBy(() -> instrument(new Price(1, 3), new Quantity(1, 8)));
        assertThatIllegalArgumentException().isThrownBy(() -> instrument(new Price(1, 2), new Quantity(1, 7)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstrumentConfig(
                        new Symbol("BTC-USD"),
                        new Asset("ETH"),
                        new Asset("USD"),
                        new Price(1, 2),
                        new Quantity(1, 8),
                        2,
                        8));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstrumentConfig(
                        new Symbol("BTC-BTC"),
                        new Asset("BTC"),
                        new Asset("BTC"),
                        new Price(1, 2),
                        new Quantity(1, 8),
                        2,
                        8));
        assertThatNullPointerException().isThrownBy(() -> instrument(null, new Quantity(1, 8)));
    }
}
