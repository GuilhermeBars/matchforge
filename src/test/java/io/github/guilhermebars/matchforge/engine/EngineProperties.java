package io.github.guilhermebars.matchforge.engine;

import static io.github.guilhermebars.matchforge.engine.Command.CancelOrder;
import static io.github.guilhermebars.matchforge.engine.Command.CreateAccount;
import static io.github.guilhermebars.matchforge.engine.Command.Deposit;
import static io.github.guilhermebars.matchforge.engine.Command.PlaceOrder;
import static io.github.guilhermebars.matchforge.engine.Command.ReplaceOrder;
import static io.github.guilhermebars.matchforge.engine.Command.Withdraw;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.A;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.B;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.BTC;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.BTC_USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.C;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.ETH;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.ETH_USD;
import static io.github.guilhermebars.matchforge.engine.EngineFixture.USD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.OrderType;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.domain.TimeInForce;
import io.github.guilhermebars.matchforge.ledger.Ledger;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class EngineProperties {
    @Provide
    Arbitrary<List<Integer>> actions() {
        return Arbitraries.integers().between(0, 1_000_000).list().ofMinSize(30).ofMaxSize(100);
    }

    @Property(tries = 60)
    void randomCommandsConserveFundsReservationsAndBalancedBooks(@ForAll("actions") List<Integer> actions) {
        var f = new EngineFixture();
        var funding = new HashMap<Asset, BigInteger>();
        funding.put(USD, BigInteger.valueOf(300_000));
        funding.put(BTC, BigInteger.valueOf(30_000));
        funding.put(ETH, BigInteger.valueOf(30_000));
        var prior = new ArrayList<PlaceOrder>();
        int step = 0;
        for (int action : actions) {
            var command = command(f.engine, action, step++, prior);
            var result = f.run(command);
            if (result.outcome().status() == CommandResult.Status.OK) {
                if (command instanceof Deposit d)
                    funding.merge(d.asset(), BigInteger.valueOf(d.amount()), BigInteger::add);
                if (command instanceof Withdraw w)
                    funding.merge(w.asset(), BigInteger.valueOf(-w.amount()), BigInteger::add);
            }
            checkIndependentInvariants(f.engine, funding);
        }
    }

    @Property(tries = 60)
    void replayAndSnapshotContinuationProduceIdenticalEventsAndState(@ForAll("actions") List<Integer> actions) {
        var left = new EngineFixture().engine;
        var right = new EngineFixture().engine;
        var prior = new ArrayList<PlaceOrder>();
        int midpoint = actions.size() / 2;
        for (int i = 0; i < actions.size(); i++) {
            if (i == midpoint) right = MatchingEngine.restore(right.snapshot());
            var command = command(left, actions.get(i), i, prior);
            var envelope = new CommandEnvelope(left.lastSequence() + 1, Instant.EPOCH.plusSeconds(i), command);
            assertThat(right.process(envelope)).isEqualTo(left.process(envelope));
            assertThat(right.snapshot()).isEqualTo(left.snapshot());
            left.assertInvariants();
            right.assertInvariants();
        }
    }

    private Command command(MatchingEngine engine, int action, int step, List<PlaceOrder> prior) {
        var random = new Random(action);
        var account = List.of(A, B, C).get(random.nextInt(3));
        var symbol = random.nextBoolean() ? BTC_USD : ETH_USD;
        var asset = symbol.equals(BTC_USD) ? BTC : ETH;
        var side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = (1 + random.nextInt(8)) * 2L, price = (4 + random.nextInt(4)) * 2L;
        int kind = random.nextInt(12);
        if (kind == 8) return new CreateAccount(random.nextBoolean() ? account : new AccountId("new" + step));
        if (kind == 0) return new Deposit(account, random.nextBoolean() ? USD : asset, 1 + random.nextInt(100));
        if (kind == 1) return new Withdraw(account, random.nextBoolean() ? USD : asset, 1 + random.nextInt(110_000));
        if (kind == 2 || kind == 3) {
            var orders = engine.book(symbol).orders();
            if (!orders.isEmpty()) {
                var order = orders.get(random.nextInt(orders.size()));
                // Usually target the owner, but also exercise ownership failures.
                var owner = random.nextInt(5) == 0 ? account : order.accountId();
                if (kind == 2) return new CancelOrder(owner, order.orderId());
                return new ReplaceOrder(owner, order.orderId(), random.nextBoolean() ? order.price() : price, quantity);
            }
            return new CancelOrder(account, new OrderId("missing"));
        }
        if ((kind == 4 || kind == 5) && !prior.isEmpty()) {
            var p = prior.get(random.nextInt(prior.size()));
            return kind == 4
                    ? p
                    : new PlaceOrder(
                            p.accountId(),
                            p.clientOrderId(),
                            p.symbol(),
                            p.side(),
                            p.type(),
                            p.timeInForce(),
                            p.price(),
                            p.quantity() + 2,
                            p.quoteBudget());
        }
        var type = kind == 6 ? OrderType.MARKET : OrderType.LIMIT;
        var tif = TimeInForce.values()[random.nextInt(3)];
        if (kind >= 9) tif = TimeInForce.GTC;
        var p = new PlaceOrder(
                account,
                new ClientOrderId("p" + step),
                symbol,
                side,
                type,
                tif,
                type == OrderType.MARKET ? 0 : price,
                kind == 7 ? 0 : quantity,
                type == OrderType.MARKET && side == Side.BUY ? (long) random.nextInt(200) : null);
        prior.add(p);
        return p;
    }

    private void checkIndependentInvariants(MatchingEngine engine, Map<Asset, BigInteger> funding) {
        var snapshot = engine.snapshot();
        var totals = new HashMap<Asset, BigInteger>();
        for (var a : snapshot.accounts())
            for (var balance : a.balances()) {
                assertThat(balance.balance().available()).isNotNegative();
                assertThat(balance.balance().reserved()).isNotNegative();
                totals.merge(
                        balance.asset(),
                        BigInteger.valueOf(balance.balance().available())
                                .add(BigInteger.valueOf(balance.balance().reserved())),
                        BigInteger::add);
                long expectedReserve = snapshot.openOrders().stream()
                        .filter(o -> o.accountId().equals(a.accountId()))
                        .filter(o -> (o.side() == Side.BUY ? USD : (o.symbol().equals(BTC_USD) ? BTC : ETH))
                                .equals(balance.asset()))
                        .mapToLong(o -> o.side() == Side.BUY ? o.price() * o.quantity() : o.quantity())
                        .sum();
                assertThat(balance.balance().reserved()).isEqualTo(expectedReserve);
            }
        assertThat(totals).isEqualTo(funding);
        for (var symbol : List.of(BTC_USD, ETH_USD)) {
            var book = engine.book(symbol);
            assertThat(book.isCrossed()).isFalse();
            if (book.bestBid().isPresent() && book.bestAsk().isPresent())
                assertThat(book.bestBid().getAsLong()).isLessThan(book.bestAsk().getAsLong());
        }
        for (var tx : snapshot.ledger())
            assertThat(Ledger.trialBalance(tx.postings()).values()).allMatch(v -> v.signum() == 0);
        assertThat(Ledger.trialBalance(snapshot.ledger().stream()
                                .flatMap(t -> t.postings().stream())
                                .toList())
                        .values())
                .allMatch(v -> v.signum() == 0);
    }
}
