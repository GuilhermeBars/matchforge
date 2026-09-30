package io.github.guilhermebars.matchforge.engine;

import static io.github.guilhermebars.matchforge.domain.OrderType.LIMIT;
import static io.github.guilhermebars.matchforge.domain.OrderType.MARKET;
import static io.github.guilhermebars.matchforge.domain.Side.BUY;
import static io.github.guilhermebars.matchforge.domain.Side.SELL;
import static io.github.guilhermebars.matchforge.domain.TimeInForce.FOK;
import static io.github.guilhermebars.matchforge.domain.TimeInForce.GTC;
import static io.github.guilhermebars.matchforge.domain.TimeInForce.IOC;
import static io.github.guilhermebars.matchforge.engine.Command.CancelOrder;
import static io.github.guilhermebars.matchforge.engine.Command.CreateAccount;
import static io.github.guilhermebars.matchforge.engine.Command.Deposit;
import static io.github.guilhermebars.matchforge.engine.Command.PlaceOrder;
import static io.github.guilhermebars.matchforge.engine.Command.ReplaceOrder;
import static io.github.guilhermebars.matchforge.engine.Command.Withdraw;
import static io.github.guilhermebars.matchforge.engine.CommandResult.Status.CANCELLED;
import static io.github.guilhermebars.matchforge.engine.CommandResult.Status.CONFLICT;
import static io.github.guilhermebars.matchforge.engine.CommandResult.Status.FILLED;
import static io.github.guilhermebars.matchforge.engine.CommandResult.Status.OPEN;
import static io.github.guilhermebars.matchforge.engine.DomainEvent.CancelReason;
import static io.github.guilhermebars.matchforge.engine.DomainEvent.Header;
import static io.github.guilhermebars.matchforge.engine.DomainEvent.OrderCancelled;
import static io.github.guilhermebars.matchforge.engine.DomainEvent.OrderReplaced;
import static io.github.guilhermebars.matchforge.engine.DomainEvent.TradeExecuted;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.A;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.B;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.BTC;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.BTC_USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.C;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.CONFIG;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.ETH;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.ETH_USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.trades;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.InstrumentConfig;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.Price;
import io.github.guilhermebars.matchforge.domain.Quantity;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.ledger.Ledger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MatchingEngineTest {
    @Test
    void priceThenFifoAndPartialFill() {
        var f = new EngineFixture();
        var expensive = f.limit(B, SELL, 12, 3).outcome().orderId();
        var first = f.limit(B, SELL, 10, 2).outcome().orderId();
        var second = f.limit(C, SELL, 10, 4).outcome().orderId();
        var result = f.limit(A, BUY, 13, 5);
        assertThat(trades(result)).extracting(TradeExecuted::makerOrderId).containsExactly(first, second);
        assertThat(trades(result)).extracting(TradeExecuted::price).containsExactly(10L, 10L);
        assertThat(trades(result)).extracting(TradeExecuted::quantity).containsExactly(2L, 3L);
        assertThat(f.engine.book(BTC_USD).orders())
                .extracting(OrderBook.OpenOrder::orderId)
                .containsExactly(second, expensive);
        assertThat(f.engine.book(BTC_USD).find(second).orElseThrow().quantity()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void sweepsLevelsAtMakerPrice(Side side) {
        var f = new EngineFixture();
        var opposite = side == BUY ? SELL : BUY;
        var first = f.limit(B, opposite, side == BUY ? 9 : 11, 2).outcome().orderId();
        var second = f.limit(C, opposite, 10, 3).outcome().orderId();
        var result = f.limit(A, side, side == BUY ? 12 : 8, 7);
        assertThat(trades(result)).extracting(TradeExecuted::makerOrderId).containsExactly(first, second);
        assertThat(trades(result)).extracting(TradeExecuted::price).containsExactly(side == BUY ? 9L : 11L, 10L);
        assertThat(result.outcome().status()).isEqualTo(OPEN);
        assertThat(result.outcome().remainingQuantity()).isEqualTo(2);
        assertThat(f.engine.book(BTC_USD).orders()).hasSize(1);
    }

    @Test
    void limitDoesNotTradeOutsideItsPrice() {
        var f = new EngineFixture();
        f.limit(B, SELL, 11, 3);
        assertThat(trades(f.limit(A, BUY, 10, 3))).isEmpty();
        assertThat(f.engine.book(BTC_USD).bestBid().orElseThrow()).isEqualTo(10);
        assertThat(f.engine.book(BTC_USD).bestAsk().orElseThrow()).isEqualTo(11);
    }

    @Test
    void depthAggregatesAndCancellationRemovesEmptyLevels() {
        var f = new EngineFixture();
        var one = f.limit(A, BUY, 10, 3).outcome().orderId();
        var two = f.limit(B, BUY, 10, 4).outcome().orderId();
        f.limit(C, BUY, 9, 5);
        f.limit(C, SELL, 12, 6);
        assertThat(f.engine.book(BTC_USD).depth(1).bids()).containsExactly(new OrderBook.DepthLevel(10, 7, 2));
        assertThat(f.engine.book(BTC_USD).depth(0).asks()).isEmpty();
        assertThatThrownBy(() -> f.engine.book(BTC_USD).depth(-1)).isInstanceOf(IllegalArgumentException.class);
        f.run(new CancelOrder(A, one));
        f.run(new CancelOrder(B, two));
        assertThat(f.engine.book(BTC_USD).bestBid().orElseThrow()).isEqualTo(9);
        assertThat(f.engine.balance(A, USD).reserved()).isZero();
    }

    @Test
    void crossedHelperDetectsEqualAndInvertedPrices() {
        var b = new OrderBook(BTC_USD);
        assertThat(b.isCrossed()).isFalse();
        b.add(new OrderBook.OpenOrder(new OrderId("1"), A, new ClientOrderId("1"), BTC_USD, BUY, 10, 1, 10));
        b.add(new OrderBook.OpenOrder(new OrderId("2"), B, new ClientOrderId("2"), BTC_USD, SELL, 10, 1, 1));
        assertThat(b.isCrossed()).isTrue();
        b.remove(new OrderId("2"));
        assertThat(b.bestAsk()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void marketNeverRestsAndCancelsRemainder(Side side) {
        var f = new EngineFixture();
        f.limit(B, side == BUY ? SELL : BUY, 10, 2);
        var result = f.special(A, side, MARKET, GTC, 0, 5, side == BUY ? 100L : null);
        assertThat(result.outcome().status()).isEqualTo(CANCELLED);
        assertThat(trades(result)).extracting(TradeExecuted::quantity).containsExactly(2L);
        assertThat(result.events())
                .filteredOn(e -> e instanceof OrderCancelled)
                .containsExactly(new OrderCancelled(
                        new Header(f.engine.lastSequence(), Instant.EPOCH.plusSeconds(f.engine.lastSequence())),
                        result.outcome().orderId(),
                        3,
                        CancelReason.MARKET_NO_LIQUIDITY));
        assertThat(f.engine.book(BTC_USD).orders()).isEmpty();
        assertThat(f.engine.balance(A, USD).reserved()).isZero();
        assertThat(f.engine.balance(A, BTC).reserved()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void marketWithNoLiquidityReleasesEverything(Side side) {
        var f = new EngineFixture();
        var before = f.engine.snapshot().accounts();
        var r = f.special(A, side, MARKET, IOC, 0, 5, side == BUY ? 100L : null);
        assertThat(r.outcome().status()).isEqualTo(CANCELLED);
        assertThat(trades(r)).isEmpty();
        assertThat(f.engine.snapshot().accounts()).isEqualTo(before);
    }

    @Test
    void marketBudgetCapsQuantityAndRefundsUnusedAtoms() {
        var f = new EngineFixture();
        f.limit(B, SELL, 9, 2);
        f.limit(C, SELL, 11, 5);
        var r = f.special(A, BUY, MARKET, GTC, 0, 9, 40L);
        assertThat(trades(r)).extracting(TradeExecuted::quantity).containsExactly(2L, 2L);
        assertThat(f.engine.balance(A, USD).available()).isEqualTo(99_960);
        assertThat(f.engine.balance(A, USD).reserved()).isZero();
        assertThat(r.events()).anyMatch(e -> e instanceof OrderCancelled c && c.reason() == CancelReason.MARKET_BUDGET);
    }

    @Test
    void marketBudgetHonorsLotsAndCanBuyNothing() {
        var f = new EngineFixture();
        f.run(new PlaceOrder(B, new ClientOrderId("sell-eth"), ETH_USD, SELL, LIMIT, GTC, 10, 8, null));
        var r = f.run(new PlaceOrder(A, new ClientOrderId("buy-eth"), ETH_USD, BUY, MARKET, IOC, 0, 8, 39L));
        assertThat(trades(r)).extracting(TradeExecuted::quantity).containsExactly(2L);
        assertThat(f.engine.balance(A, USD).available()).isEqualTo(99_980);
        var nothing = f.run(new PlaceOrder(A, new ClientOrderId("tiny-budget"), ETH_USD, BUY, MARKET, IOC, 0, 8, 1L));
        assertThat(trades(nothing)).isEmpty();
        assertThat(nothing.events())
                .anyMatch(e -> e instanceof OrderCancelled c && c.reason() == CancelReason.MARKET_BUDGET);
    }

    @Test
    void iocFillsAndReleasesRemainder() {
        var f = new EngineFixture();
        f.limit(B, SELL, 9, 2);
        var r = f.special(A, BUY, LIMIT, IOC, 11, 5, null);
        assertThat(r.outcome().remainingQuantity()).isEqualTo(3);
        assertThat(f.engine.balance(A, USD).available()).isEqualTo(99_982);
        assertThat(f.engine.balance(A, USD).reserved()).isZero();
        assertThat(r.events()).anyMatch(e -> e instanceof OrderCancelled c && c.reason() == CancelReason.IOC_REMAINDER);
    }

    @Test
    void fokKillDoesNotChangeBooksBalancesLedgerOrTradeIds() {
        var f = new EngineFixture();
        f.limit(B, SELL, 10, 2);
        f.limit(C, SELL, 12, 10);
        var before = f.engine.snapshot();
        var r = f.special(A, BUY, LIMIT, FOK, 11, 3, null);
        assertThat(r.outcome().status()).isEqualTo(CANCELLED);
        assertThat(r.events())
                .hasSize(1)
                .allMatch(e -> e instanceof OrderCancelled c && c.reason() == CancelReason.FOK_KILLED);
        var after = f.engine.snapshot();
        assertThat(after.accounts()).isEqualTo(before.accounts());
        assertThat(after.openOrders()).isEqualTo(before.openOrders());
        assertThat(after.ledger()).isEqualTo(before.ledger());
        assertThat(after.nextTradeId()).isEqualTo(before.nextTradeId());
    }

    @Test
    void fokFillsMultipleLevelsAndBudgetCanKillMarketFok() {
        var f = new EngineFixture();
        f.limit(B, SELL, 9, 2);
        f.limit(C, SELL, 10, 3);
        var before = f.engine.snapshot().openOrders();
        assertThat(f.special(A, BUY, MARKET, FOK, 0, 5, 47L).outcome().status()).isEqualTo(CANCELLED);
        assertThat(f.engine.snapshot().openOrders()).isEqualTo(before);
        var r = f.special(A, BUY, LIMIT, FOK, 11, 5, null);
        assertThat(r.outcome().status()).isEqualTo(FILLED);
        assertThat(trades(r)).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void decreaseKeepsPriorityAndReleasesDelta(Side side) {
        var f = new EngineFixture();
        var first = f.limit(A, side, 10, 5).outcome().orderId();
        f.limit(B, side, 10, 5);
        var r = f.run(new ReplaceOrder(A, first, 10, 3));
        assertThat(r.events()).anyMatch(e -> e instanceof OrderReplaced x && x.priorityRetained());
        assertThat(f.engine.balance(A, side == BUY ? USD : BTC).reserved()).isEqualTo(side == BUY ? 30 : 3);
        assertThat(trades(f.limit(C, side == BUY ? SELL : BUY, 10, 2)))
                .extracting(TradeExecuted::makerOrderId)
                .containsExactly(first);
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void increaseLosesPriorityAndReservesDelta(Side side) {
        var f = new EngineFixture();
        var first = f.limit(A, side, 10, 3).outcome().orderId();
        var second = f.limit(B, side, 10, 3).outcome().orderId();
        var r = f.run(new ReplaceOrder(A, first, 10, 4));
        assertThat(r.events()).anyMatch(e -> e instanceof OrderReplaced x && !x.priorityRetained());
        assertThat(f.engine.balance(A, side == BUY ? USD : BTC).reserved()).isEqualTo(side == BUY ? 40 : 4);
        assertThat(trades(f.limit(C, side == BUY ? SELL : BUY, 10, 4)))
                .extracting(TradeExecuted::makerOrderId)
                .containsExactly(second, first);
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void priceChangeRequeuesEvenWithQuantityDecrease(Side side) {
        var f = new EngineFixture();
        var first = f.limit(A, side, 9, 5).outcome().orderId();
        var second = f.limit(B, side, 10, 3).outcome().orderId();
        f.run(new ReplaceOrder(A, first, 10, 2));
        assertThat(trades(f.limit(C, side == BUY ? SELL : BUY, 10, 5)))
                .extracting(TradeExecuted::makerOrderId)
                .containsExactly(second, first);
    }

    @Test
    void replacementMayCrossImmediatelyUsingReleasedReservation() {
        var f = new EngineFixture();
        var id = f.limit(A, BUY, 9, 10).outcome().orderId();
        f.limit(B, SELL, 10, 3);
        f.run(new Withdraw(A, USD, 99_910));
        var r = f.run(new ReplaceOrder(A, id, 11, 8));
        assertThat(trades(r)).extracting(TradeExecuted::price).containsExactly(10L);
        assertThat(f.engine.balance(A, USD).reserved()).isEqualTo(55);
        assertThat(f.engine.balance(A, USD).available()).isEqualTo(5);
    }

    @Test
    void replacementRiskFailureLeavesOriginalPriorityAndReservation() {
        var f = new EngineFixture();
        var id = f.limit(A, BUY, 10, 3).outcome().orderId();
        f.limit(B, BUY, 10, 3);
        var before = f.engine.snapshot();
        assertThat(f.run(new ReplaceOrder(A, id, 10, 20_000)).outcome().reason())
                .isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        assertThat(f.engine.snapshot().accounts()).isEqualTo(before.accounts());
        assertThat(f.engine.snapshot().openOrders()).isEqualTo(before.openOrders());
        assertThat(trades(f.limit(C, SELL, 10, 2)))
                .extracting(TradeExecuted::makerOrderId)
                .containsExactly(id);
    }

    @Test
    void selfTradePreflightRejectsEntireSweepBeforeExternalFill() {
        var f = new EngineFixture();
        f.limit(B, SELL, 9, 2);
        f.limit(A, SELL, 10, 3);
        var before = f.engine.snapshot();
        assertThat(f.limit(A, BUY, 11, 4).outcome().reason()).isEqualTo(RejectionReason.SELF_TRADE);
        assertThat(f.engine.snapshot().accounts()).isEqualTo(before.accounts());
        assertThat(f.engine.snapshot().openOrders()).isEqualTo(before.openOrders());
        assertThat(f.engine.snapshot().ledger()).isEqualTo(before.ledger());
        assertThat(trades(f.limit(A, BUY, 11, 2))).hasSize(1);
    }

    @Test
    void selfTradePreflightAlsoProtectsReplaceAndFok() {
        var f = new EngineFixture();
        var id = f.limit(A, BUY, 8, 3).outcome().orderId();
        f.limit(B, SELL, 9, 1);
        f.limit(A, SELL, 10, 2);
        var orders = f.engine.snapshot().openOrders();
        assertThat(f.run(new ReplaceOrder(A, id, 11, 3)).outcome().reason()).isEqualTo(RejectionReason.SELF_TRADE);
        assertThat(f.special(A, BUY, LIMIT, FOK, 11, 3, null).outcome().reason())
                .isEqualTo(RejectionReason.SELF_TRADE);
        assertThat(f.engine.snapshot().openOrders()).isEqualTo(orders);
    }

    @Test
    void limitPriceImprovementRefundsImmediatelyThenCancelReleasesRest() {
        var f = new EngineFixture();
        f.limit(B, SELL, 8, 2);
        var r = f.limit(A, BUY, 10, 5);
        assertThat(f.engine.balance(A, USD).available()).isEqualTo(99_954);
        assertThat(f.engine.balance(A, USD).reserved()).isEqualTo(30);
        f.run(new CancelOrder(A, r.outcome().orderId()));
        assertThat(f.engine.balance(A, USD).available()).isEqualTo(99_984);
        assertThat(f.engine.balance(B, USD).available()).isEqualTo(100_016);
        assertThat(f.engine.balance(A, BTC).available()).isEqualTo(10_002);
    }

    @Test
    void idempotencyReturnsOriginalOutcomeWithoutEventsEvenAfterCancel() {
        var f = new EngineFixture();
        var p = f.request(A, BUY, 10, 3);
        var original = f.run(p);
        f.run(new CancelOrder(A, original.outcome().orderId()));
        var before = f.engine.snapshot();
        var retry = f.run(p);
        assertThat(retry.outcome()).isEqualTo(original.outcome());
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.events()).isEmpty();
        assertThat(f.engine.snapshot().nextOrderId()).isEqualTo(before.nextOrderId());
        assertThat(f.engine.snapshot().accounts()).isEqualTo(before.accounts());
        var conflict = f.run(new PlaceOrder(
                p.accountId(), p.clientOrderId(), p.symbol(), p.side(), p.type(), p.timeInForce(), 11, 3, null));
        assertThat(conflict.outcome().status()).isEqualTo(CONFLICT);
        assertThat(conflict.outcome().reason()).isEqualTo(RejectionReason.DUPLICATE_CLIENT_ORDER_ID);
        assertThat(conflict.events()).isEmpty();
    }

    @Test
    void rejectedRequestsRemainIdempotentAfterFundingAndAccountKeysAreIndependent() {
        var f = new EngineFixture();
        var p = f.request(A, BUY, 10, 20_000);
        var original = f.run(p);
        f.run(new Deposit(A, USD, 200_000));
        assertThat(f.run(p).outcome()).isEqualTo(original.outcome());
        var other = f.run(new PlaceOrder(B, p.clientOrderId(), BTC_USD, BUY, LIMIT, GTC, 10, 1, null));
        assertThat(other.duplicate()).isFalse();
        assertThat(other.outcome().status()).isEqualTo(OPEN);
    }

    @Test
    void snapshotJsonRoundTripRestoresFifoIdempotencyAndIdenticalContinuation() throws Exception {
        var f = new EngineFixture();
        f.limit(B, SELL, 10, 3);
        f.limit(C, SELL, 10, 5);
        var p = f.request(A, BUY, 11, 4);
        var original = f.run(p);
        f.limit(B, BUY, 8, 4);
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var snapshot = f.engine.snapshot();
        var decoded = mapper.readValue(mapper.writeValueAsBytes(snapshot), EngineSnapshot.class);
        assertThat(decoded).isEqualTo(snapshot);
        var restored = MatchingEngine.restore(decoded);
        assertThat(restored.snapshot()).isEqualTo(snapshot);
        for (var command : List.of(p, f.request(A, BUY, 10, 8), f.request(B, SELL, 8, 7))) {
            var envelope = new CommandEnvelope(f.engine.lastSequence() + 1, Instant.ofEpochSecond(42), command);
            var left = f.engine.process(envelope);
            var right = restored.process(envelope);
            assertThat(right).isEqualTo(left);
            assertThat(restored.snapshot()).isEqualTo(f.engine.snapshot());
            restored.assertInvariants();
            if (command.equals(p)) {
                assertThat(right.events()).isEmpty();
                assertThat(right.outcome()).isEqualTo(original.outcome());
            }
        }
        assertThat(snapshot.openOrders()).hasSize(2);
        assertThatThrownBy(() -> snapshot.openOrders().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void callerTimestampAndContiguousSequenceAreEnforced() {
        var engine = new MatchingEngine(CONFIG);
        var instant = Instant.parse("2000-01-01T00:00:00Z");
        var r = engine.process(new CommandEnvelope(1, instant, new CreateAccount(A)));
        assertThat(r.events())
                .allMatch(e ->
                        e.header().timestamp().equals(instant) && e.header().sequence() == 1);
        var before = engine.snapshot();
        assertThatThrownBy(() -> engine.process(new CommandEnvelope(3, instant, new CreateAccount(B))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.snapshot()).isEqualTo(before);
    }

    @Test
    void ledgerRejectsUnbalancedTransactionsAndBalancesEachAssetSeparately() {
        assertThatThrownBy(() -> new Ledger.Transaction(
                        1,
                        Instant.EPOCH,
                        "bad",
                        List.of(new Ledger.Posting(A, USD, 10), new Ledger.Posting(B, BTC, -10))))
                .isInstanceOf(IllegalArgumentException.class);
        var f = new EngineFixture();
        f.limit(B, SELL, 10, 3);
        f.limit(A, BUY, 11, 2);
        f.run(new Withdraw(A, BTC, 5));
        for (var tx : f.engine.ledgerTransactions())
            assertThat(Ledger.trialBalance(tx.postings()).values()).allMatch(x -> x.signum() == 0);
        assertThat(f.engine.ledgerTransactions())
                .anyMatch(t -> t.postings().stream().anyMatch(p -> p.accountId().equals(Ledger.EXTERNAL)));
    }

    @Test
    void configurationRejectsInconsistentSharedAssetPrecision() {
        var invalid = new InstrumentConfig(ETH_USD, ETH, USD, new Price(1, 3), new Quantity(1, 8), 3, 8);
        assertThatThrownBy(() -> new MatchingEngine(List.of(CONFIG.getFirst(), invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingEngine(List.of(CONFIG.getFirst(), CONFIG.getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marketBudgetStopsBeforeUnaffordableOwnOrder() {
        var f = new EngineFixture();
        f.limit(B, SELL, 9, 2);
        f.limit(A, SELL, 10, 3);
        var result = f.special(A, BUY, MARKET, GTC, 0, 5, 19L);
        assertThat(result.outcome().status()).isEqualTo(CANCELLED);
        assertThat(trades(result)).extracting(TradeExecuted::quantity).containsExactly(2L);
        assertThat(f.engine.book(BTC_USD).orders()).hasSize(1);
    }

    @Test
    void retryOfKilledFokRemainsKilledAfterLiquidityArrives() {
        var f = new EngineFixture();
        var p = new PlaceOrder(A, new ClientOrderId("fok"), BTC_USD, BUY, LIMIT, FOK, 10, 3, null);
        var original = f.run(p);
        f.limit(B, SELL, 9, 3);
        var retry = f.run(p);
        assertThat(retry.outcome()).isEqualTo(original.outcome());
        assertThat(retry.events()).isEmpty();
        assertThat(f.engine.book(BTC_USD).orders()).hasSize(1);
    }

    @Test
    void emptySnapshotRestoresAndUnsupportedVersionFails() {
        var snapshot = new MatchingEngine(CONFIG).snapshot();
        assertThat(MatchingEngine.restore(snapshot).snapshot()).isEqualTo(snapshot);
        var invalid = new EngineSnapshot(
                99,
                snapshot.instruments(),
                snapshot.accounts(),
                snapshot.openOrders(),
                snapshot.ledger(),
                snapshot.nextOrderId(),
                snapshot.nextTradeId(),
                snapshot.lastSequence(),
                snapshot.idempotency());
        assertThatThrownBy(() -> MatchingEngine.restore(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
