package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.OrderId;
import java.util.List;

public record CommandResult(Outcome outcome, List<DomainEvent> events, boolean duplicate) {
    public CommandResult {
        events = List.copyOf(events);
    }

    public enum Status {
        OK,
        OPEN,
        FILLED,
        CANCELLED,
        REJECTED,
        CONFLICT
    }

    public record Outcome(
            Status status,
            OrderId orderId,
            RejectionReason reason,
            long remainingQuantity,
            List<DomainEvent.TradeExecuted> trades,
            DomainEvent.CancelReason cancelReason) {
        public Outcome(
                Status status,
                OrderId orderId,
                RejectionReason reason,
                long remainingQuantity,
                List<DomainEvent.TradeExecuted> trades) {
            this(status, orderId, reason, remainingQuantity, trades, null);
        }

        public Outcome {
            trades = List.copyOf(trades);
        }
    }
}
