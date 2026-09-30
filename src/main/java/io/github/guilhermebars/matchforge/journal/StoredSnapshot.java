package io.github.guilhermebars.matchforge.journal;

import io.github.guilhermebars.matchforge.domain.OrderId;
import io.github.guilhermebars.matchforge.engine.*;
import java.util.List;

public record StoredSnapshot(long seq, String checksum, State state) {
    public record OrderState(OrderId orderId, CommandResult.Outcome outcome) {}
    public record State(int version, EngineSnapshot engine, List<OrderState> orders,
                        List<DomainEvent.TradeExecuted> recentTrades) {
        public State { orders = List.copyOf(orders); recentTrades = List.copyOf(recentTrades); }
    }
    public static StoredSnapshot create(State state, PersistenceCodec codec) {
        return new StoredSnapshot(state.engine().lastSequence(), codec.hash(state), state);
    }
    public void verify(PersistenceCodec codec) {
        if (state.version() != 1 || seq != state.engine().lastSequence() || !checksum.equals(codec.hash(state)))
            throw new IllegalStateException("Snapshot version, sequence or checksum mismatch");
    }
}
