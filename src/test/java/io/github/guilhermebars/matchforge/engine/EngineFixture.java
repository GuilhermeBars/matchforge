package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.*;
import java.time.Instant;
import java.util.List;
import static io.github.guilhermebars.matchforge.engine.Command.*;

class EngineFixture {
    static final Asset BTC = new Asset("BTC"), ETH = new Asset("ETH"), USD = new Asset("USD");
    static final Symbol BTC_USD = new Symbol("BTC-USD"), ETH_USD = new Symbol("ETH-USD");
    static final AccountId A = new AccountId("alice"), B = new AccountId("bob"), C = new AccountId("carol");
    static final List<InstrumentConfig> CONFIG = List.of(
            new InstrumentConfig(BTC_USD, BTC, USD, new Price(1, 2), new Quantity(1, 8), 2, 8),
            new InstrumentConfig(ETH_USD, ETH, USD, new Price(2, 2), new Quantity(2, 8), 2, 8));
    MatchingEngine engine = new MatchingEngine(CONFIG);
    private int client;
    EngineFixture() {
        for (var account : List.of(A, B, C)) {
            run(new CreateAccount(account));
            run(new Deposit(account, USD, 100_000));
            run(new Deposit(account, BTC, 10_000));
            run(new Deposit(account, ETH, 10_000));
        }
    }
    CommandResult run(Command command) {
        long seq = engine.lastSequence() + 1;
        var result = engine.process(new CommandEnvelope(seq, Instant.EPOCH.plusSeconds(seq), command));
        engine.assertInvariants();
        return result;
    }
    PlaceOrder request(AccountId account, Side side, long price, long quantity) {
        return new PlaceOrder(account, new ClientOrderId("c" + ++client), BTC_USD,
                side, OrderType.LIMIT, TimeInForce.GTC, price, quantity, null);
    }
    CommandResult limit(AccountId account, Side side, long price, long quantity) {
        return run(request(account, side, price, quantity));
    }
    CommandResult special(AccountId account, Side side, OrderType type, TimeInForce tif,
                          long price, long quantity, Long budget) {
        return run(new PlaceOrder(account, new ClientOrderId("c" + ++client), BTC_USD, side, type, tif, price, quantity, budget));
    }
    static List<DomainEvent.TradeExecuted> trades(CommandResult result) { return result.outcome().trades(); }
}
