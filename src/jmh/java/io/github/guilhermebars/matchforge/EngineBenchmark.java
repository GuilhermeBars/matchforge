package io.github.guilhermebars.matchforge;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.Asset;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.InstrumentConfig;
import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.domain.OrderType;
import io.github.guilhermebars.matchforge.domain.Price;
import io.github.guilhermebars.matchforge.domain.Quantity;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.domain.TimeInForce;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.engine.CommandEnvelope;
import io.github.guilhermebars.matchforge.engine.CommandResult;
import io.github.guilhermebars.matchforge.engine.MatchingEngine;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class EngineBenchmark {
    private static final Asset BTC = new Asset("BTC");
    private static final Asset USD = new Asset("USD");
    private static final Symbol SYMBOL = new Symbol("BTC-USD");
    private static final AccountId BUYER = new AccountId("buyer");
    private static final AccountId SELLER = new AccountId("seller");
    private MatchingEngine engine;
    private CommandEnvelope[] flow;

    @Setup(Level.Invocation)
    public void prepareBatch() {
        engine = new MatchingEngine(
                List.of(new InstrumentConfig(SYMBOL, BTC, USD, new Price(1, 2), new Quantity(1, 8), 2, 8)));
        long sequence = 0;
        for (AccountId account : List.of(BUYER, SELLER)) {
            engine.process(envelope(++sequence, new Command.CreateAccount(account)));
            engine.process(envelope(++sequence, new Command.Deposit(account, BTC, 100_000_000L)));
            engine.process(envelope(++sequence, new Command.Deposit(account, USD, 1_000_000_000_000_000L)));
        }
        for (int level = 1; level <= 20; level++) {
            engine.process(envelope(
                    ++sequence, order(BUYER, "seed-bid" + level, Side.BUY, 5_000_000 - level, TimeInForce.GTC)));
            engine.process(envelope(
                    ++sequence, order(SELLER, "seed-ask" + level, Side.SELL, 5_000_000 + level, TimeInForce.GTC)));
        }
        // 256 cycles, each with two resting limits, one crossing IOC, and one cancel.
        // The 40 seeded orders precede the untouched bid in cycle i (ID 3*i+41).
        flow = new CommandEnvelope[1024];
        for (int i = 0; i < 256; i++) {
            long spread = 1 + i % 20;
            flow[4 * i] = envelope(++sequence, order(BUYER, "bid" + i, Side.BUY, 5_000_000 - spread, TimeInForce.GTC));
            flow[4 * i + 1] =
                    envelope(++sequence, order(SELLER, "ask" + i, Side.SELL, 5_000_000 + spread, TimeInForce.GTC));
            flow[4 * i + 2] = envelope(++sequence, order(BUYER, "cross" + i, Side.BUY, 5_000_020, TimeInForce.IOC));
            flow[4 * i + 3] =
                    envelope(++sequence, new Command.CancelOrder(BUYER, new OrderId(Long.toString(3L * i + 41))));
        }
    }

    private static Command.PlaceOrder order(AccountId account, String id, Side side, long price, TimeInForce tif) {
        return new Command.PlaceOrder(
                account, new ClientOrderId(id), SYMBOL, side, OrderType.LIMIT, tif, price, 1000, null);
    }

    private static CommandEnvelope envelope(long sequence, Command command) {
        return new CommandEnvelope(sequence, Instant.EPOCH.plusNanos(sequence), command);
    }

    @Benchmark
    @OperationsPerInvocation(1024)
    public void mixedOrderFlow(Blackhole blackhole) {
        for (CommandEnvelope command : flow) {
            CommandResult result = engine.process(command);
            if (result.outcome().status() == CommandResult.Status.REJECTED) {
                throw new IllegalStateException("Invalid benchmark flow: " + result.outcome());
            }
            blackhole.consume(result);
        }
    }
}
