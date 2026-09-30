package io.github.guilhermebars.matchforge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifiersTest {
    @Test
    void preservesIdentityWithoutNormalization() {
        assertThat(new Symbol("BTC-USD").toString()).isEqualTo("BTC-USD");
        assertThat(new Asset("BTC").toString()).isEqualTo("BTC");
        assertThat(new AccountId("FEES").toString()).isEqualTo("FEES");
        assertThat(new OrderId("order-1")).isEqualTo(new OrderId("order-1"));
        assertThat(new OrderId("order-1").toString()).isEqualTo("order-1");
        assertThat(new ClientOrderId("client:1").toString()).isEqualTo("client:1");
        assertThat(new AccountId("abc")).isNotEqualTo(new AccountId("ABC"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " leading", "trailing ", "a/b", "a\nb"})
    void rejectsMalformedIds(String value) {
        assertThatThrownBy(() -> new AccountId(value))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThatThrownBy(() -> new OrderId(value))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThatThrownBy(() -> new ClientOrderId(value))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }

    @Test
    void checksLimitsAndCanonicalAssetSymbols() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountId("a".repeat(129)));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderId("a".repeat(129)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientOrderId("a".repeat(129)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Asset("btc"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Symbol("btc-usd"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Symbol("BTCUSD"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Asset("A".repeat(13)));
        assertThatNullPointerException().isThrownBy(() -> new Symbol(null));
        assertThatNullPointerException().isThrownBy(() -> new Asset(null));
    }
}
