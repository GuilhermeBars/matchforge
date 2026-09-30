package io.github.guilhermebars.matchforge.engine;

import io.github.guilhermebars.matchforge.domain.InstrumentConfig;
import io.github.guilhermebars.matchforge.ledger.Ledger;
import io.github.guilhermebars.matchforge.risk.AccountBook;
import java.util.List;

/** JSON-friendly concrete records only; no polymorphic or non-string map keys. */
public record EngineSnapshot(
        int version,
        List<InstrumentConfig> instruments,
        List<AccountBook.Account> accounts,
        List<OrderBook.OpenOrder> openOrders,
        List<Ledger.Transaction> ledger,
        long nextOrderId,
        long nextTradeId,
        long lastSequence,
        List<IdempotencyEntry> idempotency) {
    public record IdempotencyEntry(Command.PlaceOrder request, CommandResult.Outcome outcome) {}

    public EngineSnapshot {
        instruments = List.copyOf(instruments);
        accounts = List.copyOf(accounts);
        openOrders = List.copyOf(openOrders);
        ledger = List.copyOf(ledger);
        idempotency = List.copyOf(idempotency);
    }
}
