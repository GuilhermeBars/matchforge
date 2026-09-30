package io.github.guilhermebars.matchforge.engine;

import static io.github.guilhermebars.matchforge.domain.OrderType.LIMIT;
import static io.github.guilhermebars.matchforge.domain.OrderType.MARKET;
import static io.github.guilhermebars.matchforge.domain.Side.BUY;
import static io.github.guilhermebars.matchforge.domain.Side.SELL;
import static io.github.guilhermebars.matchforge.domain.TimeInForce.GTC;
import static io.github.guilhermebars.matchforge.domain.TimeInForce.IOC;
import static io.github.guilhermebars.matchforge.engine.Command.CancelOrder;
import static io.github.guilhermebars.matchforge.engine.Command.CreateAccount;
import static io.github.guilhermebars.matchforge.engine.Command.Deposit;
import static io.github.guilhermebars.matchforge.engine.Command.PlaceOrder;
import static io.github.guilhermebars.matchforge.engine.Command.ReplaceOrder;
import static io.github.guilhermebars.matchforge.engine.Command.Withdraw;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.A;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.B;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.BTC_USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.ETH_USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.USD;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.ACCOUNT_EXISTS;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INSUFFICIENT_FUNDS;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INVALID_AMOUNT;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INVALID_LOT;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INVALID_ORDER;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INVALID_QUANTITY;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INVALID_QUOTE_BUDGET;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.INVALID_TICK;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.NOT_ORDER_OWNER;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.NUMERIC_OVERFLOW;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.UNKNOWN_ACCOUNT;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.UNKNOWN_ASSET;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.UNKNOWN_ORDER;
import static io.github.guilhermebars.matchforge.engine.RejectionReason.UNKNOWN_SYMBOL;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.ledger.Ledger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RiskTest {
    @ParameterizedTest
    @EnumSource(
            value = RejectionReason.class,
            names = {"DUPLICATE_CLIENT_ORDER_ID", "SELF_TRADE"},
            mode = EnumSource.Mode.EXCLUDE)
    void businessRejectionsHaveNoEconomicSideEffects(RejectionReason reason) {
        var f = new EngineFixture();
        var otherOrder = f.limit(B, BUY, 10, 2).outcome().orderId();
        var client = new ClientOrderId("invalid");
        Command command =
                switch (reason) {
                    case INSUFFICIENT_FUNDS -> f.request(A, BUY, 10, 20_000);
                    case UNKNOWN_SYMBOL ->
                        new PlaceOrder(A, client, new Symbol("DOGE-USD"), BUY, LIMIT, GTC, 10, 2, null);
                    case INVALID_TICK -> new PlaceOrder(A, client, ETH_USD, BUY, LIMIT, GTC, 11, 2, null);
                    case INVALID_LOT -> new PlaceOrder(A, client, ETH_USD, BUY, LIMIT, GTC, 10, 3, null);
                    case INVALID_QUANTITY -> f.request(A, BUY, 10, 0);
                    case UNKNOWN_ACCOUNT -> f.request(new AccountId("missing"), BUY, 10, 3);
                    case ACCOUNT_EXISTS -> new CreateAccount(A);
                    case UNKNOWN_ORDER -> new CancelOrder(A, new OrderId("missing"));
                    case NOT_ORDER_OWNER -> new CancelOrder(A, otherOrder);
                    case INVALID_AMOUNT -> new Deposit(A, USD, 0);
                    case INVALID_QUOTE_BUDGET -> new PlaceOrder(A, client, BTC_USD, BUY, MARKET, GTC, 0, 2, null);
                    case INVALID_ORDER -> new PlaceOrder(A, client, BTC_USD, BUY, MARKET, GTC, 10, 2, 20L);
                    case UNKNOWN_ASSET -> new Deposit(A, new Asset("DOGE"), 2);
                    case NUMERIC_OVERFLOW -> f.request(A, BUY, Long.MAX_VALUE, 2);
                    default -> throw new AssertionError(reason);
                };
        var before = f.engine.snapshot();
        var result = f.run(command);
        assertThat(result.outcome().reason()).isEqualTo(reason);
        assertThat(result.events()).hasSize(1).allMatch(e -> e instanceof DomainEvent.OrderRejected);
        var after = f.engine.snapshot();
        assertThat(after.accounts()).isEqualTo(before.accounts());
        assertThat(after.openOrders()).isEqualTo(before.openOrders());
        assertThat(after.ledger()).isEqualTo(before.ledger());
        assertThat(after.nextOrderId()).isEqualTo(before.nextOrderId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    void rejectsNonPositiveQuantitiesAndFunding(long quantity) {
        var f = new EngineFixture();
        assertThat(f.limit(A, SELL, 10, quantity).outcome().reason()).isEqualTo(INVALID_QUANTITY);
        assertThat(f.run(new Deposit(A, USD, quantity)).outcome().reason()).isEqualTo(INVALID_AMOUNT);
        assertThat(f.run(new Withdraw(A, USD, quantity)).outcome().reason()).isEqualTo(INVALID_AMOUNT);
    }

    @Test
    void rejectsOversellingOverBudgetAndWithdrawalOfReservedFunds() {
        var f = new EngineFixture();
        assertThat(f.limit(A, SELL, 10, 10_001).outcome().reason()).isEqualTo(INSUFFICIENT_FUNDS);
        assertThat(f.special(A, BUY, MARKET, IOC, 0, 1, 100_001L).outcome().reason())
                .isEqualTo(INSUFFICIENT_FUNDS);
        f.limit(A, BUY, 10, 10_000);
        assertThat(f.run(new Withdraw(A, USD, 1)).outcome().reason()).isEqualTo(INSUFFICIENT_FUNDS);
        assertThat(f.run(new Withdraw(new AccountId("missing"), USD, 1))
                        .outcome()
                        .reason())
                .isEqualTo(UNKNOWN_ACCOUNT);
        assertThat(f.run(new CreateAccount(Ledger.EXTERNAL)).outcome().reason()).isEqualTo(INVALID_ORDER);
    }

    @Test
    void overflowDepositRejectedBeforeMutationAndMaximumHoldingsCanSettle() {
        var f = new EngineFixture();
        f.run(new Deposit(A, USD, Long.MAX_VALUE - 300_000));
        var before = f.engine.snapshot().accounts();
        assertThat(f.run(new Deposit(B, USD, 1)).outcome().reason()).isEqualTo(NUMERIC_OVERFLOW);
        assertThat(f.engine.snapshot().accounts()).isEqualTo(before);
        f.limit(B, SELL, 10, 3);
        f.limit(A, BUY, 11, 3);
        f.run(new Withdraw(A, USD, 1));
        assertThat(f.run(new Deposit(B, USD, 1)).outcome().status()).isEqualTo(CommandResult.Status.OK);
    }

    @Test
    void replaceAndCancelValidateOwnershipAndExistence() {
        var f = new EngineFixture();
        var id = f.limit(B, SELL, 10, 3).outcome().orderId();
        assertThat(f.run(new ReplaceOrder(A, id, 11, 3)).outcome().reason()).isEqualTo(NOT_ORDER_OWNER);
        assertThat(f.run(new ReplaceOrder(B, new OrderId("missing"), 11, 3))
                        .outcome()
                        .reason())
                .isEqualTo(UNKNOWN_ORDER);
        assertThat(f.run(new ReplaceOrder(B, id, 0, 3)).outcome().reason()).isEqualTo(INVALID_TICK);
        assertThat(f.run(new ReplaceOrder(B, id, 10, 0)).outcome().reason()).isEqualTo(INVALID_QUANTITY);
        f.run(new CancelOrder(B, id));
        assertThat(f.run(new CancelOrder(B, id)).outcome().reason()).isEqualTo(UNKNOWN_ORDER);
    }

    @Test
    void malformedOrderCombinationsRejectDeterministically() {
        var f = new EngineFixture();
        assertThat(f.special(A, BUY, MARKET, GTC, 0, 2, -1L).outcome().reason()).isEqualTo(INVALID_QUOTE_BUDGET);
        assertThat(f.special(A, SELL, MARKET, GTC, 0, 2, 10L).outcome().reason())
                .isEqualTo(INVALID_ORDER);
        assertThat(f.special(A, BUY, LIMIT, GTC, 10, 2, 10L).outcome().reason()).isEqualTo(INVALID_ORDER);
        assertThat(f.special(A, null, LIMIT, GTC, 10, 2, null).outcome().reason())
                .isEqualTo(INVALID_ORDER);
    }
}
