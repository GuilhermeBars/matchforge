package io.github.guilhermebars.matchforge;

import io.github.guilhermebars.matchforge.domain.AccountId;
import io.github.guilhermebars.matchforge.domain.ClientOrderId;
import io.github.guilhermebars.matchforge.domain.OrderType;
import io.github.guilhermebars.matchforge.domain.Side;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.domain.TimeInForce;
import io.github.guilhermebars.matchforge.engine.Command;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class JournalBenchmark {
    private final PersistenceCodec codec = new PersistenceCodec();
    private final Command command = new Command.PlaceOrder(
            new AccountId("buyer"),
            new ClientOrderId("bench-1"),
            new Symbol("BTC-USD"),
            Side.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            5_000_000,
            1000,
            null);

    @Benchmark
    public String serializeCommand() {
        return codec.encode(command);
    }
}
