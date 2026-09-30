package io.github.guilhermebars.matchforge.service;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.InstrumentConfig;
import io.github.guilhermebars.matchforge.domain.OrderType;
import io.github.guilhermebars.matchforge.domain.Price;
import io.github.guilhermebars.matchforge.domain.Quantity;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.domain.TimeInForce;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.events.EventPublisher;
import io.github.guilhermebars.matchforge.events.InProcessEventPublisher;
import io.github.guilhermebars.matchforge.journal.Journal;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import io.github.guilhermebars.matchforge.journal.SnapshotStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class ServiceFixture {
    public static final AccountId A = new AccountId("alice"), B = new AccountId("bob");
    public static final Asset BTC = new Asset("BTC"), USD = new Asset("USD");
    public static final Symbol SYMBOL = new Symbol("BTC-USD");
    public static final List<InstrumentConfig> CONFIG =
            List.of(new InstrumentConfig(SYMBOL, BTC, USD, new Price(1, 0), new Quantity(1, 0), 0, 0));

    public static EngineService service(Journal journal, SnapshotStore snapshots, int interval) {
        return service(journal, snapshots, interval, 16, new InProcessEventPublisher(), new SimpleMeterRegistry());
    }

    public static EngineService service(
            Journal journal,
            SnapshotStore snapshots,
            int interval,
            int capacity,
            EventPublisher publisher,
            SimpleMeterRegistry metrics) {
        return new EngineService(
                CONFIG,
                journal,
                snapshots,
                new PersistenceCodec(),
                publisher,
                metrics,
                interval,
                capacity,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"), ZoneOffset.UTC));
    }

    public static Command.PlaceOrder order(AccountId a, String client, Side side, long price, long qty) {
        return new Command.PlaceOrder(
                a, new ClientOrderId(client), SYMBOL, side, OrderType.LIMIT, TimeInForce.GTC, price, qty, null);
    }

    public static void fund(EngineService service) {
        for (var account : List.of(A, B)) {
            service.submit(new Command.CreateAccount(account)).join();
            service.submit(new Command.Deposit(account, USD, 100000)).join();
            service.submit(new Command.Deposit(account, BTC, 10000)).join();
        }
    }

    private ServiceFixture() {}
}
