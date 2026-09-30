package io.github.guilhermebars.matchforge.events;
import io.github.guilhermebars.matchforge.engine.DomainEvent;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import org.springframework.kafka.core.KafkaTemplate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Acknowledged sends, bounded by producer delivery timeout. No exactly-once delivery claim. */
public final class KafkaEventPublisher implements EventPublisher {
    public record WireEvent(int version, String eventType, DomainEvent event) {}
    private final KafkaTemplate<String, String> kafka;
    private final PersistenceCodec codec;
    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, PersistenceCodec codec) {
        this.kafka = kafka; this.codec = codec;
    }
    @Override public void publish(List<DomainEvent> events) { publishWithAccount(events, null); }
    @Override public void publish(io.github.guilhermebars.matchforge.engine.CommandEnvelope envelope, List<DomainEvent> events) {
        var account = switch (envelope.command()) {
            case io.github.guilhermebars.matchforge.engine.Command.CreateAccount c -> c.accountId();
            case io.github.guilhermebars.matchforge.engine.Command.Deposit c -> c.accountId();
            case io.github.guilhermebars.matchforge.engine.Command.Withdraw c -> c.accountId();
            case io.github.guilhermebars.matchforge.engine.Command.PlaceOrder c -> c.accountId();
            case io.github.guilhermebars.matchforge.engine.Command.CancelOrder c -> c.accountId();
            case io.github.guilhermebars.matchforge.engine.Command.ReplaceOrder c -> c.accountId();
        };
        publishWithAccount(events, account == null ? "unknown" : account.value());
    }
    private void publishWithAccount(List<DomainEvent> events, String account) {
        for (var event : events) {
            String type = switch (event) {
                case DomainEvent.AccountCreated e -> "account-created";
                case DomainEvent.FundsDeposited e -> "funds-deposited";
                case DomainEvent.FundsWithdrawn e -> "funds-withdrawn";
                case DomainEvent.OrderAccepted e -> "order-accepted";
                case DomainEvent.OrderRejected e -> "order-rejected";
                case DomainEvent.OrderRested e -> "order-rested";
                case DomainEvent.TradeExecuted e -> "trade-executed";
                case DomainEvent.OrderCancelled e -> "order-cancelled";
                case DomainEvent.OrderReplaced e -> "order-replaced";
                case DomainEvent.LedgerPosted e -> "ledger-posted";
            };
            String key = switch (event) {
                case DomainEvent.TradeExecuted e -> e.symbol().value();
                case DomainEvent.OrderRested e -> e.order().symbol().value();
                case DomainEvent.AccountCreated e -> e.accountId().value();
                case DomainEvent.FundsDeposited e -> e.accountId().value();
                case DomainEvent.FundsWithdrawn e -> e.accountId().value();
                case DomainEvent.OrderAccepted e -> e.accountId().value();
                case DomainEvent.OrderRejected e -> e.accountId() == null ? "unknown" : e.accountId().value();
                case DomainEvent.LedgerPosted e -> e.transaction().postings().getFirst().accountId().value();
                // Cancel/replace routing comes from the originating command.
                default -> java.util.Objects.requireNonNull(account, "Command context required for lifecycle events");
            };
            try { kafka.send("matchforge.events", key, codec.encode(new WireEvent(1, type, event))).get(35, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Kafka interrupted", e); }
            catch (Exception e) { throw new IllegalStateException("Kafka publication failed", e); }
        }
    }
}

